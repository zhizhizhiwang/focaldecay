package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.mixin.client.LevelRendererAccessor;
import com.zhizhiwang.focal_decay.mixin.client.RenderChunkRegionAccessor;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.GuidedConcept;
import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端渲染缓存（设计大纲 §7）。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护 {@code targetCache}：方块位置 -> 突变目标 BlockState（仅存"有变化"的暴露方块）；</li>
 *   <li>维护 {@code evaluated}：本周期已经判定过的位置（负缓存，供面剔除路径做 O(1) 查询）；</li>
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

    /** 某个维度收到的区域数据（原型机位置 + 切比雪夫半径 + 方块诞生周期）。 */
    private static final class RegionData {
        final List<ClientPrototype> prototypes;
        final Map<BlockPos, Long> birthPeriods;

        RegionData(List<ClientPrototype> prototypes, Map<BlockPos, Long> birthPeriods) {
            this.prototypes = List.copyOf(prototypes);
            this.birthPeriods = Map.copyOf(birthPeriods);
        }
    }

    /** 客户端侧原型机效果镜像。 */
    private record ClientPrototype(BlockPos center, int radius, String type,
                                   Set<Block> trainedBlocks, Set<String> trainedEntities,
                                   int bioEnergy, String concept, int progress, double q, int copies) {
    }

    /**
     * 引导模型的渲染期形态：概念邻域池 + 半径 + 强度。
     * 每次扫描一个区块节时从 {@link RegionData} 解析一次，循环体内只做半径比较和
     * 一次 {@code boolean[]} 成员判定——旧实现是逐方块解析标签 ID 再线性扫标签成员。
     */
    private record GuidedModel(BlockPos center, int radius, ClassifiedPool pool, double strength) {
    }

    /** pos.asLong() -> 突变目标（含所属周期，防止跨周期读到旧值）。 */
    private final ConcurrentHashMap<Long, Entry> targetCache = new ConcurrentHashMap<>();
    /**
     * 本周期已经"判定过"的位置（有幽灵的、以及判定为没有幽灵的都在里面）。
     * <p>
     * 这是给面剔除路径用的<b>负缓存</b>：{@code BlockShouldRenderFaceMixin} 每个可见方块要问 6 次
     * "邻居显示成什么"，而绝大多数邻居是没有幽灵的。只靠 {@link #targetCache} 的话，
     * 每次未命中都要重跑一遍完整判定（阶段 1 一个方块 ~200 ns），6 次 × 4096 个方块就是毫秒级；
     * 有了这张表，重复查询退化成一次哈希查找。
     * <p>
     * 与 {@link #targetCache} 一起在周期边界 / 标签变化 / 观测者状态变化时整体清空。
     */
    private final Set<Long> evaluated = ConcurrentHashMap.newKeySet();
    /** SectionPos.asLong()：当前存在幽灵方块的节。 */
    private final Set<Long> activeSections = ConcurrentHashMap.newKeySet();
    /** 每节幽灵方块数量，保证 activeSections 精确回收。 */
    private final ConcurrentHashMap<Long, Integer> sectionCounts = new ConcurrentHashMap<>();
    /** 待扫描节队列（按到玩家距离升序）。 */
    private final Queue<Long> pendingSections = new ArrayDeque<>();
    /** 各维度的保护区域数据（由 SyncRegionDataPacket 同步）。 */
    private final Map<ResourceKey<Level>, RegionData> regionData = new ConcurrentHashMap<>();

    private volatile Frustum frustum;
    private volatile ClientLevel level;
    private volatile long worldDays;
    /** 观测者核心已激活：失焦终止，不再生成/保留幽灵预览。 */
    private volatile boolean observerOnline;
    /**
     * 当前周期编号。编译线程要读它（面剔除的快路径），所以必须是 volatile。
     * 在 {@link #tick()} 里随缓存清空一起更新。
     */
    private volatile long lastPeriodIndex = Long.MIN_VALUE;
    /** 上次扫描所用的突变查表实例；标签更新会让它换新，此时必须丢弃整份幽灵缓存。 */
    private volatile MutationIndex lastIndex;
    private int scanCooldown;

    private PostChain veil;
    private Object veilResourceManager;
    private float veilTime;
    /**
     * 一次呼吸循环的时长（秒）。20 秒：足够慢，让"浓度变化"像潮汐而不是脉动。
     * <p>
     * 调参记录：实际 1 秒（单位换算错误所致，像脉动）→ 20 秒。中间试过"12 秒""15 秒"，
     * 但那时单位是错的，数值没有参考价值。
     */
    private static final float VEIL_CYCLE_SECONDS = 20.0F;
    /**
     * 呼吸幅度。0.85 ± 0.15 是"能察觉在变、但不会去数它"的档位；
     * 更大的幅度（0.75 ± 0.25）配合波纹会显得一下一下地脉动。
     */
    private static final float VEIL_BREATHE_MID = 0.85F;
    private static final float VEIL_BREATHE_AMPLITUDE = 0.15F;
    private boolean veilLoadFailed;
    /**
     * 重聚焦（观测者上线）后遮罩的残留强度：1 = 全强度，0 = 已完全移除。
     * <p>
     * 每帧按 {@code postProcessRefocusFadeTicks} 递减，到 0 就卸载整个 PostChain。
     * 世界重新回到失焦状态（或开关被打开）时复位为 1。
     */
    private float veilRefocusFade = 1.0F;

    /** 呼吸曲线：cos 使往复两端平滑（速度为零），节拍均匀。 */
    private static float breatheFor(float phase) {
        return VEIL_BREATHE_MID + VEIL_BREATHE_AMPLITUDE * Mth.cos(phase * Mth.TWO_PI);
    }
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
     * <p>
     * 所有"判定结果为不替换"的分支都会写进 {@link #evaluated}（负缓存），
     * 否则面剔除路径上每个邻居查询都要把整套判定重跑一遍。
     */
    public BlockState resolve(RenderChunkRegion region, BlockPos pos, BlockState original) {
        long key = pos.asLong();
        if (isProtected(pos, original, currentStage())) {
            evaluated.add(key);
            return original;
        }
        if (observerOnline) {
            evaluated.add(key);
            return original;
        }
        if (!(region instanceof RenderChunkRegionAccessor accessor)) {
            return original;
        }
        if (!(accessor.focaldecay$getLevel() instanceof ClientLevel clientLevel) || clientLevel != this.level) {
            return original;
        }

        int stage = currentStage();
        MutationIndex index = MutationIndexes.get(clientLevel.dimension());
        if (!isCandidate(original, index)) {
            evaluated.add(key);
            return original;
        }

        long period = currentPeriod(clientLevel);
        Entry entry = targetCache.get(key);
        if (entry != null && entry.period == period) {
            return entry.state;
        }
        if (entry != null) {
            targetCache.remove(key, entry);
            decrSection(pos);
        }

        BlockState target = computeTarget(clientLevel, pos, original, index, resolveGuidedModels());
        if (target == original || !isRenderableTarget(target) || !isExposed(region, pos)) {
            evaluated.add(key);
            return original;
        }
        putEntry(pos, target, period);
        return target;
    }

    /**
     * 面剔除路径的"邻居显示成什么"（由 {@code BlockShouldRenderFaceMixin} 调用，跑在区块编译线程上）。
     * <p>
     * 网格用的是幽灵状态，剔除判据也必须用幽灵状态，否则两边对不上：真实石头（幽灵玻璃）旁边的方块
     * 会把朝玻璃的那一面剔掉，而那一面在原版语义里是要画的——结果就是透过玻璃看进方块内部，像破了个洞。
     * <p>
     * 代价控制：这个函数对每个可见方块要被问 6 次，所以顺序是
     * ①正缓存命中 → ②负缓存命中 → ③才做完整判定。绝大多数邻居落在前两种。
     */
    public BlockState ghostState(RenderChunkRegion region, BlockPos pos, BlockState real) {
        long key = pos.asLong();
        Entry entry = targetCache.get(key);
        if (entry != null && entry.period == lastPeriodIndex) {
            return entry.state;
        }
        if (evaluated.contains(key)) {
            return real; // 本周期已判定过，确定没有幽灵
        }
        return resolve(region, pos, real);
    }

    /**
     * 中键选取（pick block）时使用的"可见状态"：
     * 优先返回缓存的幽灵目标；未命中则按当前周期现算。
     */
    public BlockState visibleState(ClientLevel level, BlockPos pos) {
        BlockState original = level.getBlockState(pos);
        if (isProtected(pos, original, currentStage())) {
            return original;
        }
        if (observerOnline) {
            return original;
        }
        if (original.isAir()) {
            return original; // 空气无法拾取
        }
        int stage = currentStage();
        MutationIndex index = MutationIndexes.get(level.dimension());
        if (!isCandidate(original, index)) {
            return original;
        }
        long key = pos.asLong();
        long period = currentPeriod(level);
        Entry entry = targetCache.get(key);
        if (entry != null && entry.period == period) {
            return entry.state;
        }
        BlockState target = computeTarget(level, pos, original, index, resolveGuidedModels());
        if (target != original && isRenderableTarget(target)) {
            return target;
        }
        return original;
    }

    /**
     * 挖掘进度用"可见目标"（2026-08-21）：与渲染预览同一公式；
     * 优先读缓存，未命中时计算并写回——保证挖掘每 tick 只是 O(1) 缓存查询，
     * 累积回退扫描只发生在周期边界。
     */
    public BlockState miningState(ClientLevel level, BlockPos pos) {
        BlockState original = level.getBlockState(pos);
        if (isProtected(pos, original, currentStage()) || observerOnline) {
            return original;
        }
        if (original.isAir()) {
            return original;
        }
        int stage = currentStage();
        MutationIndex index = MutationIndexes.get(level.dimension());
        if (!isCandidate(original, index)) {
            return original;
        }
        long key = pos.asLong();
        long period = currentPeriod(level);
        Entry entry = targetCache.get(key);
        if (entry != null) {
            if (entry.period == period) {
                return entry.state;
            }
            targetCache.remove(key, entry);
            decrSection(pos);
        }
        BlockState target = computeTarget(level, pos, original, index, resolveGuidedModels());
        if (target == original || !isRenderableTarget(target) || !isExposed(level, pos)) {
            return original;
        }
        putEntry(pos, target, period);
        return target;
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
            regionData.clear();
            worldDays = 0;
            observerOnline = false;
            level = null;
            lastPeriodIndex = Long.MIN_VALUE;
            return;
        }

        if (current != level) {
            level = current;
            clearCache();
            lastIndex = null;
            lastPeriodIndex = Long.MIN_VALUE;
        }
        // 标签/配置变化会换掉整个查表实例（MutationIndexes 在 TagsUpdatedEvent 时清空缓存），
        // 此时旧幽灵全部作废：候选集与源门控都可能已经变了。
        MutationIndex index = MutationIndexes.get(current.dimension());
        if (index != lastIndex) {
            lastIndex = index;
            clearCache();
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

    /**
     * 每帧渲染世界结束时调用：推进 observer_veil 后处理。
     *
     * @param frameDeltaTicks <b>每帧真实时间</b>增量（{@code DeltaTracker#getRealtimeDeltaTicks}），
     *                        不是 {@code getGameTimeDeltaTicks}。见下方动画计时的说明。
     */
    public void updateVeil(float frameDeltaTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (!FocalDecayConfig.POST_PROCESS_ENABLED.get()
                || mc.level == null
                || mc.gameRenderer.currentEffect() != null) {
            closeVeil();
            return;
        }

        // 每帧真实时间（秒）。动画计时与遮罩淡出都要用，所以先算出来。
        float frameSeconds = Mth.clamp(frameDeltaTicks, 0.0F, 2.0F) / 20.0F;

        // ── 重聚焦之后移除遮罩 ───────────────────────────────────────────────
        // 遮罩画的就是"失焦带来的不安定"，观测者核心上线之后这份不安定已经结束了，
        // 继续留着抖动属于漏做状态处理。默认淡出（也遮住核心激活那一瞬的硬切），
        // 淡到 0 就整个卸载 PostChain，连每帧一次的全屏 pass 都不再付。
        if (!FocalDecayConfig.POST_PROCESS_AFTER_REFOCUS.get() && observerOnline) {
            int fadeTicks = Math.max(0, FocalDecayConfig.POST_PROCESS_REFOCUS_FADE_TICKS.get());
            if (veilRefocusFade > 0.0F) {
                veilRefocusFade = fadeTicks <= 0
                        ? 0.0F
                        : Math.max(0.0F, veilRefocusFade - frameSeconds / (fadeTicks / 20.0F));
            }
            if (veilRefocusFade <= 0.0F) {
                if (veil != null) {
                    closeVeil();
                    LOGGER.info("Focal Decay: observer veil removed after refocus");
                }
                return;
            }
        } else {
            // 开关打开，或世界又回到失焦状态：恢复全强度（重新加载由下面的分支负责）。
            veilRefocusFade = 1.0F;
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

        // ── 动画计时 ─────────────────────────────────────────────────────────
        // 时间源与单位，两个都必须正确：
        //
        // 1) 用「每帧真实时间」而不是游戏 tick 时间。累加 DeltaTracker#getGameTimeDeltaTicks() 时，
        //    它只在发生 tick 的那一帧返回 1、其余帧返回 0，相位呈锯齿状推进。
        //
        // 2) getRealtimeDeltaTicks() 的单位是 <b>tick</b>，不是秒：源码是
        //    (time - lastUiMs) / msPerTick，而 msPerTick = 1000/20 = 50ms。
        //    60fps 下一帧 16.7ms → 0.333，即每秒累加 20。换算成秒要 / 20。
        //    （frameSeconds 在本方法开头就算好了，淡出也要用。）
        veilTime += frameSeconds;
        float phase = (veilTime / VEIL_CYCLE_SECONDS) % 1.0F;

        float intensity = FocalDecayConfig.POST_INTENSITY.get().floatValue();
        // Fade 在 shader 里同时乘在漂移、色散、着色三项上，所以淡到 0 就是"整个效果消失"，
        // 而不是只去掉色调、留下抖动。
        veil.setUniform("Fade", Mth.clamp(intensity * breatheFor(phase) * veilRefocusFade, 0.0F, 1.0F));

        // ⚠️ 波纹必须用自建的连续时间 uniform，<b>不能用内置的 Time</b>。
        // PostChain 每帧把 Time 归一化到 [0,1) 并在满 20 tick 时硬回绕：
        //     this.time += partialTicks;
        //     while (this.time > 20.0F) { this.time -= 20.0F; }
        //     postpass.process(this.time / 20.0F);
        // 于是 shader 里任何 `Time * f` 在回绕点的相位差都是 2π·f —— 只有 f 取整数才连续。
        // 换句话说：<b>用 Time 就永远做不出周期长于 1 秒的平滑动画</b>。
        // 我们自己累计一个只增不回绕的秒数，周期就能任意取。
        veil.setUniform("TotalTime", veilTime);
        veil.process(frameDeltaTicks);
        // 与原版 postEffect.process 后一致：恢复主渲染目标绑定，供后续手部/UI 使用
        mc.getMainRenderTarget().bindWrite(true);
    }

    public void captureFrustum(Frustum frustum) {
        this.frustum = frustum;
    }

    /** 服务端同步末日天数；阶段变化会改变周期/概率/影响范围，需要清缓存重算。 */
    public void setWorldData(long days, boolean observerOnline) {
        boolean changed = this.worldDays != days || this.observerOnline != observerOnline;
        if (changed) {
            this.worldDays = days;
            this.observerOnline = observerOnline;
            clearCache();
        }
    }

    /** 观测者核心激活完成（客户端）：播放粒子/音效与胜利提示。 */
    public void notifyCoreActivated(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(Component.translatable("message.focal_decay.core_activated"), true);
        RandomSource random = mc.level.random;
        for (int i = 0; i < 120; i++) {
            double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 3.0;
            double y = pos.getY() + 0.5 + random.nextDouble() * 4.0;
            double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 3.0;
            mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0.0, 0.05, 0.0);
        }
        mc.level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F, false);
    }

    // ------------------------------------------------------------------
    // 稳定锚保护区域（网络同步）
    // ------------------------------------------------------------------

    /** 收到服务端同步的区域数据（主线程）。 */
    public void applyRegionData(ResourceKey<Level> dimension, List<SyncRegionDataPacket.PrototypeData> prototypes,
                                long[] birthPositions, long[] birthPeriods) {
        List<ClientPrototype> prototypeList = new ArrayList<>(prototypes.size());
        for (SyncRegionDataPacket.PrototypeData p : prototypes) {
            prototypeList.add(new ClientPrototype(
                    BlockPos.of(p.pos()), p.radius(), p.type(),
                    parseBlocks(p.trainedTargets()), Set.copyOf(p.trainedEntities()), p.bioEnergy(),
                    p.concept(), p.progress(), p.q(), p.copies()));
        }

        Map<BlockPos, Long> newBirths = new HashMap<>();
        for (int i = 0; i < birthPositions.length; i++) {
            newBirths.put(BlockPos.of(birthPositions[i]), birthPeriods[i]);
        }

        RegionData old = regionData.get(dimension);
        Set<BlockPos> changed = new HashSet<>();
        if (old == null) {
            changed.addAll(newBirths.keySet());
        } else {
            for (Map.Entry<BlockPos, Long> entry : newBirths.entrySet()) {
                Long oldBirth = old.birthPeriods.get(entry.getKey());
                if (oldBirth == null || !oldBirth.equals(entry.getValue())) {
                    changed.add(entry.getKey());
                }
            }
            for (BlockPos pos : old.birthPeriods.keySet()) {
                if (!newBirths.containsKey(pos)) {
                    changed.add(pos);
                }
            }
        }

        regionData.put(dimension, new RegionData(prototypeList, newBirths));
        refreshRegionData(changed);
    }

    /**
     * 收到<b>单条</b>诞生周期变化（{@link com.zhizhiwang.focal_decay.network.SyncBirthPeriodPacket}）。
     * {@code period < 0} 表示删除。放置/破坏/交互转换都只发这一条，
     * 取代了旧实现"每次变化都重发整张表"的做法。
     */
    public void applyBirthPeriod(ResourceKey<Level> dimension, long packedPos, long period) {
        RegionData old = regionData.get(dimension);
        if (old == null) {
            return; // 整表还没到，等下一次全量同步即可
        }
        BlockPos pos = BlockPos.of(packedPos);
        Map<BlockPos, Long> births = new HashMap<>(old.birthPeriods);
        if (period < 0) {
            births.remove(pos);
        } else {
            births.put(pos, period);
        }
        regionData.put(dimension, new RegionData(old.prototypes, births));
        refreshBirths(Set.of(pos));
    }

    /**
     * 只有诞生周期变化时用：只清理这几个位置，不做整表扫描。
     * <p>
     * 放置/破坏方块是高频操作，而"保护范围可能变了"的整表扫描是 O(幽灵数) 的，
     * 不能挂在每一次放置上。真正的整表扫描留给 {@link #applyRegionData}（原型机变化/登录）。
     */
    private void refreshBirths(Set<BlockPos> changed) {
        if (targetCache.isEmpty() || changed.isEmpty()) {
            return;
        }
        Set<Long> dirty = new HashSet<>();
        for (BlockPos pos : changed) {
            long key = pos.asLong();
            if (targetCache.remove(key) != null) {
                decrSection(pos);
                evaluated.remove(key);
                dirty.add(SectionPos.asLong(pos));
            }
        }
        for (long sectionKey : dirty) {
            markSectionDirty(SectionPos.of(sectionKey));
        }
    }

    /** 把同步来的方块 ID 列表解析成方块集合（保护判定要在热路径上做 O(1) 命中）。 */
    private static Set<Block> parseBlocks(List<String> ids) {
        Set<Block> blocks = new HashSet<>(ids.size());
        for (String id : ids) {
            try {
                blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)));
            } catch (Exception ignored) {
                // 非法 ID 忽略（与服务端解析一致）
            }
        }
        return blocks;
    }

    /** 当前客户端所在维度的保护数据；未同步或无保护返回 null。 */
    private RegionData currentRegionData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return regionData.get(mc.level.dimension());
    }

    /**
     * 阶段感知的保护形态（与服务端 {@code MutationPoolManager#protectionInfo} 同一逻辑）：
     * 阶段3语义锁定转为软保护（每周期按强度掷"守住"骰子），生物稳定/完全稳定仍硬保护。
     */
    public MutationHelper.Protection protectionInfo(BlockPos pos, BlockState state, int stage) {
        RegionData data = currentRegionData();
        if (data == null) {
            return MutationHelper.Protection.NONE;
        }
        MutationHelper.Protection result = MutationHelper.Protection.NONE;
        for (ClientPrototype prototype : data.prototypes) {
            if (!withinRadius(pos, prototype)) {
                continue;
            }
            if (ObserverModelData.TYPE_TOTAL.equals(prototype.type())) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_BIO.equals(prototype.type()) && prototype.bioEnergy() > 0) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_CANDIDATE.equals(prototype.type())
                    && prototype.progress() >= ObserverModelData.requiredCandidatePoints(prototype.copies())) {
                return MutationHelper.Protection.HARD; // 已完成候选 = 完全稳定
            }
            if (ObserverModelData.TYPE_SEMANTIC_LOCK.equals(prototype.type())) {
                if (!prototype.trainedBlocks().contains(state.getBlock())) {
                    continue;
                }
                if (stage >= 3) {
                    double strength = FocalDecayConfig.SEMANTIC_LOCK_STAGE3_STRENGTH.get()
                            * prototype.q();
                    strength = Math.max(0.0, Math.min(1.0, strength));
                    if (strength > result.softChance()) {
                        result = new MutationHelper.Protection(false, strength);
                    }
                } else {
                    return MutationHelper.Protection.HARD;
                }
            }
        }
        return result;
    }

    /** 硬保护判定（渲染/扫描早期跳过用；阶段3语义锁定不再是硬保护）。 */
    public boolean isProtected(BlockPos pos, BlockState state, int stage) {
        return protectionInfo(pos, state, stage).hard();
    }

    private static boolean withinRadius(BlockPos pos, ClientPrototype prototype) {
        return Math.max(Math.abs(pos.getX() - prototype.center().getX()),
                Math.max(Math.abs(pos.getY() - prototype.center().getY()),
                        Math.abs(pos.getZ() - prototype.center().getZ()))) <= prototype.radius();
    }

    private static boolean withinRadius(BlockPos pos, GuidedModel model) {
        return Math.max(Math.abs(pos.getX() - model.center().getX()),
                Math.max(Math.abs(pos.getY() - model.center().getY()),
                        Math.abs(pos.getZ() - model.center().getZ()))) <= model.radius();
    }

    /** 该方块的诞生周期；未同步或世界原生返回 -1。 */
    public long getBlockBirthPeriod(BlockPos pos) {
        RegionData data = currentRegionData();
        return data == null ? -1L : data.birthPeriods.getOrDefault(pos, -1L);
    }

    /** 区域数据更新后：清掉保护范围内或诞生状态变化的幽灵缓存并触发重编译。 */
    private void refreshRegionData(Set<BlockPos> changedBirths) {
        if (targetCache.isEmpty()) {
            return;
        }
        Set<Long> dirty = new HashSet<>();
        targetCache.forEach((key, entry) -> {
            BlockPos pos = BlockPos.of(key);
            BlockState real = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.getBlockState(pos)
                    : Blocks.AIR.defaultBlockState();
            if (isProtected(pos, real, currentStage()) || changedBirths.contains(pos)) {
                targetCache.remove(key, entry);
                decrSection(pos);
                evaluated.remove(key);
                dirty.add(SectionPos.asLong(pos));
            }
        });
        for (long sectionKey : dirty) {
            markSectionDirty(SectionPos.of(sectionKey));
        }
    }

    // ------------------------------------------------------------------
    // 表面扫描
    // ------------------------------------------------------------------

    private void scanSurfaces(ClientLevel level) {
        if (observerOnline) {
            return; // 失焦终止：无幽灵可扫描
        }
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
        int stage = currentStage();
        // 逐节取一次查表和引导模型：4096 个方块共用，循环体里不再有任何标签/字符串操作。
        MutationIndex index = MutationIndexes.get(level.dimension());
        List<GuidedModel> guided = resolveGuidedModels();
        boolean changed = false;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockPos pos = min.offset(x, y, z);
                    long key = pos.asLong();
                    BlockState state = level.getBlockState(pos);

                    if (!isCandidate(state, index) || isProtected(pos, state, stage) || !isExposed(level, pos)) {
                        if (removeEntry(pos, key)) {
                            changed = true;
                        }
                        continue;
                    }
                    evaluated.add(key);

                    BlockState target = computeTarget(level, pos, state, index, guided);
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

    /**
     * 目标计算。与服务端 {@code MutationTargets#resolveServer} 是同一个函数、
     * 同一份查表、同一组公式；差别只在上下文怎么装配（客户端从同步过来的区域数据里取）。
     *
     * @param index  本次扫描/查询所用的突变查表（一次扫描只取一次，不要逐方块去取）
     * @param guided 本次扫描预解析的引导模型（见 {@link #resolveGuidedModels()}）
     */
    private BlockState computeTarget(ClientLevel level, BlockPos pos, BlockState original,
                                     MutationIndex index, List<GuidedModel> guided) {
        if (observerOnline) {
            return original;
        }
        int stage = currentStage();
        return MutationHelper.resolve(original, pos, worldSeed(level), currentPeriod(level), index,
                MutationHelper.mutationChance(stage), guidedBias(guided, pos, original, stage),
                protectionInfo(pos, original, stage), getBlockBirthPeriod(pos));
    }

    /**
     * 把已同步的引导模型解析成"半径 + 概念池 + 强度"的可直接查询形态。
     * 每次扫描一个区块节解析一次：{@code MutationIndexes#tagged} 有缓存，
     * 但也没必要在每个方块上重复走一遍。
     */
    private List<GuidedModel> resolveGuidedModels() {
        RegionData data = currentRegionData();
        Minecraft mc = Minecraft.getInstance();
        if (data == null || mc.level == null || data.prototypes.isEmpty()) {
            return List.of();
        }
        MutationIndex index = MutationIndexes.get(mc.level.dimension());
        List<GuidedModel> models = new ArrayList<>();
        for (ClientPrototype prototype : data.prototypes) {
            if (!ObserverModelData.TYPE_GUIDED.equals(prototype.type()) || prototype.concept().isEmpty()) {
                continue;
            }
            ClassifiedPool pool = index.tagged(prototype.concept());
            if (pool.isEmpty()) {
                continue;
            }
            models.add(new GuidedModel(prototype.center(), prototype.radius(), pool, prototype.q()));
        }
        return models;
    }

    /**
     * 客户端引导偏向（与服务端 {@code MutationPoolManager#getGuidedBias} 同一公式）：
     * 取"源方块是概念成员且 q 最大"的引导模型生效，否则不引导。
     * 概念邻域与成员判定都来自预解析的池，循环体里只有半径比较和一次 {@code boolean[]} 读。
     */
    private static GuidedBias guidedBias(List<GuidedModel> models, BlockPos pos, BlockState original, int stage) {
        GuidedBias best = null;
        double bestQ = 0.0;
        for (GuidedModel model : models) {
            if (!withinRadius(pos, model) || !model.pool().contains(original.getBlock())) {
                continue;
            }
            double q = GuidedConcept.effectiveQ(model.strength(), stage);
            if (q <= bestQ) {
                continue;
            }
            bestQ = q;
            best = new GuidedBias(model.pool(), q);
        }
        return best == null ? GuidedBias.NONE : best;
    }

    private long currentPeriod(ClientLevel level) {
        return MutationHelper.blockPeriod(level.getGameTime());
    }

    private int currentStage() {
        return MutationHelper.currentStage(worldDays);
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

    /**
     * 候选（转换源）：常规模型渲染 + 是突变源。
     * <p>
     * "是不是突变源"现在完全由预计算的 {@link MutationIndex} 决定：完整方块、数据包登记过的
     * 形态类成员、且没被 {@code mutation_immune} 豁免。
     */
    private static boolean isCandidate(BlockState state, MutationIndex index) {
        return state.getRenderShape() == RenderShape.MODEL && index.isSource(state.getBlock());
    }

    /** 目标可渲染：常规模型，且不带方块实体/流体。 */
    private static boolean isRenderableTarget(BlockState target) {
        return target.getRenderShape() == RenderShape.MODEL
                && !target.hasBlockEntity()
                && target.getFluidState().isEmpty();
    }

    /**
     * 是否暴露（能看见）。
     * <p>
     * <b>判据必须是"邻面遮挡形状是否为完整面"，不能只看 {@code canOcclude()}</b>。
     * {@code canOcclude()} 只是"这个方块有能力遮挡"的开关，雪片（高度 2/16）、半砖都是 true，
     * 但它们只挡住相邻面的一小部分——玩家明明看得见，方块却被判为不可见、不参与材质替换，
     * 于是"被雪覆盖的方块看起来没有突变"。
     * <p>
     * 也不能用 {@code isSolidRender}：那要求<b>碰撞形状</b>填满整格（{@code canOcclude && 形状是完整方块}），
     * 对雪片同样返回 false，一刀切掉太多。{@link #coversFaceFully} 才是这一面真正被挡住的判据。
     */
    private static boolean isExposed(ClientLevel level, BlockPos pos) {
        return isExposed(level, pos, level);
    }

    /** Mixin 路径用区块编译区域的 3x3 chunk 副本判断暴露面，避免跨线程读主世界。 */
    private static boolean isExposed(RenderChunkRegion region, BlockPos pos) {
        return isExposed(region, pos, region);
    }

    private static boolean isExposed(BlockGetter getter, BlockPos pos, BlockGetter shapeSource) {
        for (Direction direction : DIRECTIONS) {
            BlockState neighbor = getter.getBlockState(pos.relative(direction));
            if (neighbor.isAir() || !coversFaceFully(neighbor, shapeSource, pos.relative(direction), direction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该方块是否把朝向 {@code face} 的那一面<b>完全</b>挡住。
     * <p>
     * 用"邻面遮挡形状"而非碰撞形状：前者正是原版 {@code Block.shouldRenderFace} 所用的判据，
     * 因此与游戏自身的剔除口径一致——雪片/半砖返回不完整面 → 判为挡不住 → 邻块保持可见。
     */
    private static boolean coversFaceFully(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        if (!state.canOcclude()) {
            return false;
        }
        VoxelShape shape = state.getFaceOcclusionShape(level, pos, face.getOpposite());
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds();
        double eps = 1.0E-4;
        return box.minX <= eps && box.maxX >= 1.0 - eps
                && box.minY <= eps && box.maxY >= 1.0 - eps
                && box.minZ <= eps && box.maxZ >= 1.0 - eps;
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
        evaluated.add(key);
    }

    /**
     * 撤销一个幽灵条目。
     * <p>
     * <b>不动 {@link #evaluated}</b>：这个位置在本周期内确实已经判定过了，
     * 把负缓存一起删掉只会让面剔除路径反复重跑完整判定。真正需要"重新判定"的场合
     * （保护范围变化、诞生周期变化）由 {@code refreshRegionData} / {@code refreshBirths}
     * 显式地把位置从 {@code evaluated} 里摘掉。
     */
    private boolean removeEntry(BlockPos pos, long key) {
        if (targetCache.remove(key) == null) {
            return false;
        }
        decrSection(pos);
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
        evaluated.clear();
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

    private void closeVeil() {
        if (veil != null) {
            veil.close();
            veil = null;
        }
        veilResourceManager = null;
    }
}
