package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.mixin.client.LevelRendererAccessor;
import com.zhizhiwang.focal_decay.mixin.client.RenderChunkRegionAccessor;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端渲染缓存（设计大纲 §7）。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护 {@code targetCache}：方块位置 -> 突变目标 BlockState（仅存"有变化"的暴露方块）；</li>
 *   <li>维护 {@code visibleSurfaces}：当前判定为暴露面的方块集合；</li>
 *   <li>按 {@code surface_update_frequency} 帧用 Frustum 裁剪后扫描附近区块，更新缓存并触发区块重编译；</li>
 *   <li>突变周期切换时清空缓存并让受影响区块重编译；</li>
 *   <li>管理 observer_veil 后处理着色器（阶段强度淡化）。</li>
 * </ul>
 * 服务端与客户端使用相同种子公式，保证预览与真实转换一致；
 * 多人模式的世界种子暂缺（待 §10 网络同步），单人可以经 IntegratedServer 取得。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientRenderCache {
    private static final Logger LOGGER = FocalDecay.LOGGER;
    public static final ClientRenderCache INSTANCE = new ClientRenderCache();

    private static final Direction[] DIRECTIONS = Direction.values();
    /** 每次表面更新最多扫描的区块节数。 */
    private static final int SCAN_SECTION_BUDGET = 12;
    /** 表面扫描半径硬上限（区块数），避免配置过大拖垮主线程。 */
    private static final int MAX_SCAN_CHUNK_RADIUS = 8;
    /** 玩家所在节上下各扫描的节数。 */
    private static final int SCAN_VERTICAL_SECTIONS = 6;

    private static final class Entry {
        final BlockState state;
        final long period;

        Entry(BlockState state, long period) {
            this.state = state;
            this.period = period;
        }
    }

    /** pos.asLong() -> 突变目标（含所属周期，防止跨周期读到旧值）。 */
    private final ConcurrentHashMap<Long, Entry> targetCache = new ConcurrentHashMap<>();
    /** pos.asLong()：当前已知暴露面的方块。 */
    private final Set<Long> visibleSurfaces = ConcurrentHashMap.newKeySet();
    /** SectionPos.asLong()：当前存在幽灵方块的节。 */
    private final Set<Long> activeSections = ConcurrentHashMap.newKeySet();
    /** 每节幽灵方块数量，保证 activeSections 精确回收。 */
    private final ConcurrentHashMap<Long, Integer> sectionCounts = new ConcurrentHashMap<>();
    /** 待扫描节队列（按到玩家距离升序）。 */
    private final Queue<Long> pendingSections = new ArrayDeque<>();

    private volatile Frustum frustum;
    private volatile ClientLevel level;
    private volatile MutationPool pool = MutationPool.empty(0);
    private long lastPeriodIndex = Long.MIN_VALUE;
    private int scanCooldown;

    private PostChain veil;
    private Object veilResourceManager;
    private float veilTime;
    private boolean veilLoadFailed;
    private int veilWidth;
    private int veilHeight;

    private ClientRenderCache() {
    }

    // ------------------------------------------------------------------
    // Mixin 调用（区块编译线程/主线程）
    // ------------------------------------------------------------------

    /**
     * 区块编译时决定某个位置实际渲染的方块状态。
     * 未命中缓存时惰性计算并写回，保证首次编译即有预览。
     */
    public BlockState resolve(RenderChunkRegion region, BlockPos pos, BlockState original) {
        if (!isCandidate(original)) {
            return original;
        }
        if (!(region instanceof RenderChunkRegionAccessor accessor)) {
            return original;
        }
        if (!(accessor.focaldecay$getLevel() instanceof ClientLevel clientLevel) || clientLevel != this.level) {
            return original;
        }

        long key = pos.asLong();
        long period = currentPeriod(clientLevel);
        Entry entry = targetCache.get(key);
        if (entry != null && entry.period == period) {
            return entry.state;
        }
        if (entry != null) {
            targetCache.remove(key, entry);
            decrSection(pos);
        }

        BlockState target = computeTarget(clientLevel, pos, original);
        if (target == original || !isRenderableTarget(target) || !isExposed(region, pos)) {
            return original;
        }
        putEntry(pos, target, period);
        return target;
    }

    /**
     * 中键选取（pick block）时使用的"可见状态"：
     * 优先返回缓存的幽灵目标；未命中则按当前周期现算（空气目标回退原方块，避免取到空物品）。
     */
    public BlockState visibleState(ClientLevel level, BlockPos pos) {
        BlockState original = level.getBlockState(pos);
        if (!isCandidate(original)) {
            return original;
        }
        long key = pos.asLong();
        long period = currentPeriod(level);
        Entry entry = targetCache.get(key);
        if (entry != null && entry.period == period) {
            return entry.state;
        }
        BlockState target = computeTarget(level, pos, original);
        if (target != original && isRenderableTarget(target) && !target.isAir()) {
            return target;
        }
        return original;
    }

    // ------------------------------------------------------------------
    // 主线程 Tick（由 ClientRenderEvents 调用）
    // ------------------------------------------------------------------

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel current = mc.level;
        if (current == null) {
            if (level != null || !targetCache.isEmpty() || !activeSections.isEmpty()) {
                clearCache();
            }
            level = null;
            pool = MutationPool.empty(0);
            lastPeriodIndex = Long.MIN_VALUE;
            return;
        }

        if (current != level) {
            level = current;
            clearCache();
            rebuildPool(current);
            lastPeriodIndex = Long.MIN_VALUE;
        } else if (pool.isEmpty()) {
            rebuildPool(current); // 标签同步晚于世界加载时的自愈
        }

        long period = currentPeriod(current);
        if (period != lastPeriodIndex) {
            int sections = activeSections.size();
            int entries = targetCache.size();
            lastPeriodIndex = period;
            clearCache();
            if (sections > 0 || entries > 0) {
                LOGGER.info("Focal Decay: period {} — cleared {} ghost entries in {} sections, scheduled recompile",
                        period, entries, sections);
            }
        }

        if (--scanCooldown <= 0) {
            scanCooldown = Math.max(1, FocalDecayConfig.SURFACE_UPDATE_FREQUENCY.get());
            scanSurfaces(current);
        }
    }

    /** 每帧渲染世界结束时调用：推进 observer_veil 后处理。 */
    public void updateVeil(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!FocalDecayConfig.POST_PROCESS_ENABLED.get()
                || mc.level == null
                || mc.gameRenderer.currentEffect() != null) {
            closeVeil();
            return;
        }

        Object resourceManager = mc.getResourceManager();
        if (veil != null && resourceManager != veilResourceManager) {
            closeVeil(); // 资源重载（F3+T）后重建
        }
        if (veil == null) {
            try {
                veil = new PostChain(
                        mc.getTextureManager(),
                        mc.getResourceManager(),
                        mc.getMainRenderTarget(),
                        ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "shaders/post/observer_veil.json")
                );
                veil.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
                veilResourceManager = resourceManager;
                veilWidth = mc.getWindow().getWidth();
                veilHeight = mc.getWindow().getHeight();
                veilLoadFailed = false;
                LOGGER.info("Focal Decay: observer veil shader loaded");
            } catch (Exception e) {
                if (!veilLoadFailed) {
                    LOGGER.warn("Failed to load observer veil shader", e);
                    veilLoadFailed = true;
                }
                closeVeil();
                return;
            }
        }

        // 窗口缩放时同步后处理目标尺寸，否则输出会被错误拉伸/缩放
        int windowWidth = mc.getWindow().getWidth();
        int windowHeight = mc.getWindow().getHeight();
        if (windowWidth != veilWidth || windowHeight != veilHeight) {
            veil.resize(windowWidth, windowHeight);
            veilWidth = windowWidth;
            veilHeight = windowHeight;
        }

        veilTime += partialTick;
        float intensity = FocalDecayConfig.POST_INTENSITY.get().floatValue();
        float breathe = 0.75F + 0.25F * (float) Math.sin(veilTime * 0.05);
        veil.setUniform("Fade", Math.max(0.0F, Math.min(1.0F, intensity * breathe)));
        veil.process(partialTick);
    }

    public void captureFrustum(Frustum frustum) {
        this.frustum = frustum;
    }

    // ------------------------------------------------------------------
    // 表面扫描
    // ------------------------------------------------------------------

    private void scanSurfaces(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (pendingSections.isEmpty()) {
            rebuildScanQueue(level, mc.player.blockPosition());
        }
        int budget = SCAN_SECTION_BUDGET;
        while (budget-- > 0 && !pendingSections.isEmpty()) {
            long sectionKey = pendingSections.poll();
            if (scanSection(level, sectionKey)) {
                markSectionDirty(SectionPos.of(sectionKey));
            }
        }
    }

    /** 重建扫描队列：加载范围内、视锥可见、非空区块节，按距离升序。 */
    private void rebuildScanQueue(ClientLevel level, BlockPos center) {
        pendingSections.clear();
        Frustum f = frustum;
        SectionPos centerSection = SectionPos.of(center);
        int radius = Math.min(
                Math.max(2, FocalDecayConfig.MAX_RENDER_DISTANCE.get()),
                MAX_SCAN_CHUNK_RADIUS
        );
        List<long[]> entries = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerSection.x() + dx;
                int chunkZ = centerSection.z() + dz;
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int dy = -SCAN_VERTICAL_SECTIONS; dy <= SCAN_VERTICAL_SECTIONS; dy++) {
                    int sectionY = centerSection.y() + dy;
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    SectionPos sec = SectionPos.of(chunkX, sectionY, chunkZ);
                    if (f != null && !f.isVisible(new AABB(
                            sec.minBlockX(), sec.minBlockY(), sec.minBlockZ(),
                            sec.maxBlockX() + 1, sec.maxBlockY() + 1, sec.maxBlockZ() + 1
                    ))) {
                        continue;
                    }
                    long ddx = sec.x() - centerSection.x();
                    long ddy = sec.y() - centerSection.y();
                    long ddz = sec.z() - centerSection.z();
                    entries.add(new long[]{sec.asLong(), ddx * ddx + ddy * ddy + ddz * ddz});
                }
            }
        }
        entries.sort(Comparator.comparingLong(e -> e[1]));
        for (long[] entry : entries) {
            pendingSections.add(entry[0]);
        }
    }

    /** 扫描一个区块节：只保留暴露面候选方块的目标缓存，返回是否有变化（需要重编译）。 */
    private boolean scanSection(ClientLevel level, long sectionKey) {
        SectionPos section = SectionPos.of(sectionKey);
        BlockPos min = section.origin();
        long period = currentPeriod(level);
        boolean changed = false;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockPos pos = min.offset(x, y, z);
                    long key = pos.asLong();
                    BlockState state = level.getBlockState(pos);

                    if (!isCandidate(state) || !isExposed(level, pos)) {
                        if (removeEntry(pos, key)) {
                            changed = true;
                        }
                        continue;
                    }
                    visibleSurfaces.add(key);

                    BlockState target = computeTarget(level, pos, state);
                    if (target == state || !isRenderableTarget(target)) {
                        if (removeEntry(pos, key)) {
                            changed = true;
                        }
                        continue;
                    }

                    Entry prev = targetCache.put(key, new Entry(target, period));
                    if (prev == null || prev.period != period || prev.state != target) {
                        if (prev == null) {
                            incrSection(pos);
                        }
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 目标计算
    // ------------------------------------------------------------------

    private BlockState computeTarget(ClientLevel level, BlockPos pos, BlockState original) {
        MutationPool pool = this.pool;
        if (pool.isEmpty()) {
            return original;
        }
        long gameTick = level.getGameTime();
        long period = currentPeriod(level);
        double chance = MutationHelper.mutationChance(MutationHelper.currentStage(gameTick));
        return MutationHelper.getTarget(original, pos, worldSeed(level), period, pool.snapshot(), chance);
    }

    private static long currentPeriod(ClientLevel level) {
        return MutationHelper.periodIndex(level.getGameTime(), FocalDecayConfig.BASE_INTERVAL.get());
    }

    /**
     * 世界种子：单人可以走集成服务器；多人模式暂缺，待 §10 的 SyncRegionDataPacket 同步。
     */
    private static long worldSeed(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server != null && server.overworld() != null) {
            return server.overworld().getSeed();
        }
        return 0L;
    }

    /** 候选：非空气、无方块实体、不在黑名单、常规模型渲染。 */
    private static boolean isCandidate(BlockState state) {
        return !state.isAir()
                && !state.hasBlockEntity()
                && !state.is(ModTags.Blocks.CONVERSION_BLACKLIST)
                && state.getRenderShape() == RenderShape.MODEL;
    }

    /** 目标可渲染：空气（消失预览）或常规模型，且不带方块实体/流体。 */
    private static boolean isRenderableTarget(BlockState target) {
        return (target.isAir() || target.getRenderShape() == RenderShape.MODEL)
                && !target.hasBlockEntity()
                && target.getFluidState().isEmpty();
    }

    private static boolean isExposed(ClientLevel level, BlockPos pos) {
        for (Direction direction : DIRECTIONS) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.isAir() || !neighbor.canOcclude()) {
                return true;
            }
        }
        return false;
    }

    /** Mixin 路径用区块编译区域的 3x3 chunk 副本判断暴露面，避免跨线程读主世界。 */
    private static boolean isExposed(RenderChunkRegion region, BlockPos pos) {
        for (Direction direction : DIRECTIONS) {
            BlockState neighbor = region.getBlockState(pos.relative(direction));
            if (neighbor.isAir() || !neighbor.canOcclude()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 缓存维护
    // ------------------------------------------------------------------

    private void putEntry(BlockPos pos, BlockState target, long period) {
        long key = pos.asLong();
        Entry prev = targetCache.put(key, new Entry(target, period));
        if (prev == null) {
            incrSection(pos);
        }
        visibleSurfaces.add(key);
    }

    private boolean removeEntry(BlockPos pos, long key) {
        if (targetCache.remove(key) == null) {
            return false;
        }
        decrSection(pos);
        visibleSurfaces.remove(key);
        return true;
    }

    private void incrSection(BlockPos pos) {
        long section = SectionPos.asLong(pos);
        sectionCounts.merge(section, 1, Integer::sum);
        activeSections.add(section);
    }

    private void decrSection(BlockPos pos) {
        long section = SectionPos.asLong(pos);
        int count = sectionCounts.merge(section, -1, Integer::sum);
        if (count <= 0) {
            sectionCounts.remove(section);
            activeSections.remove(section);
        }
    }

    /** 清空缓存并让受影响区块节重编译。 */
    private void clearCache() {
        if (targetCache.isEmpty() && activeSections.isEmpty()) {
            return;
        }
        for (long sectionKey : activeSections) {
            markSectionDirty(SectionPos.of(sectionKey));
        }
        targetCache.clear();
        visibleSurfaces.clear();
        activeSections.clear();
        sectionCounts.clear();
    }

    /** 世界渲染器未就绪（viewArea 为 null）时跳过，避免世界加载/卸载过渡期 NPE。 */
    private void markSectionDirty(SectionPos section) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) {
            return;
        }
        if (((LevelRendererAccessor) mc.levelRenderer).focaldecay$getViewArea() == null) {
            return;
        }
        mc.levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
    }

    /** 从已同步的方块标签重建全局池（与服务器排序一致：按注册表 id 升序）。 */
    private void rebuildPool(ClientLevel level) {
        List<Block> blocks = new ArrayList<>();
        level.registryAccess().lookupOrThrow(Registries.BLOCK)
                .get(ModTags.Blocks.GLOBAL_MUTATION_POOL)
                .ifPresent(holders -> holders.forEach(holder -> blocks.add(holder.value())));
        this.pool = MutationPool.of(blocks, 0);
    }

    private void closeVeil() {
        if (veil != null) {
            veil.close();
            veil = null;
        }
        veilResourceManager = null;
    }
}
