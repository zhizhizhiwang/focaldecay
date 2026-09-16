package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.mixin.client.LevelRendererAccessor;
import com.zhizhiwang.focal_decay.mixin.client.RenderChunkRegionAccessor;
import com.zhizhiwang.focal_decay.network.SyncClientViewPacket;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.GuidedConcept;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
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
import net.neoforged.neoforge.network.PacketDistributor;
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
import java.util.concurrent.atomic.LongAdder;

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
 * 服务端与客户端使用同一个解析函数（{@code MutationHelper#resolve}）；
 * "算得一样"由 {@link MutationSettings} 保证——客户端只用服务端同步下来的那一份输入快照，
 * 收到之前不产出任何幽灵（2026-09-17，见 {@code SyncMutationSettingsPacket}）。
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

    /**
     * 一条幽灵：某个位置显示成什么、属于哪个周期、以及<b>它是从哪个真实方块算出来的</b>。
     * <p>
     * {@code real} 是缓存的<b>有效期判据</b>（2026-09-17 加）：幽灵是 {@code (真实方块, 位置, 种子, 周期)}
     * 的函数，真实方块一变，这条幽灵就是错的。只按周期判断有效性会漏掉"周期没变但方块变了"——
     * 最常见的就是玩家把方块挖掉：真实方块成了空气，缓存里那条"石头显示成钻石矿"还在，
     * 于是邻块朝这里的那一面继续被当成被挡住、剔掉不画，挖出来的洞要等下一轮表面扫描才出现。
     */
    private static final class Entry {
        final BlockState state;
        final BlockState real;
        final long period;

        Entry(BlockState state, BlockState real, long period) {
            this.state = state;
            this.real = real;
            this.period = period;
        }

        /** 这条幽灵现在还成立吗：真实方块没变、且还在同一个周期里。 */
        boolean validFor(BlockState current, long currentPeriod) {
            return this.real == current && this.period == currentPeriod;
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
                                   boolean bioActive, String concept, int progress, double q, int copies) {
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
    /**
     * 本周期已经"判定过"的位置 -> 判定时的真实方块（有幽灵的、以及判定为没有幽灵的都在里面）。
     * <p>
     * 这是给面剔除路径用的<b>负缓存</b>：{@code BlockShouldRenderFaceMixin} 每个可见方块要问 6 次
     * "邻居显示成什么"，而绝大多数邻居是没有幽灵的。只靠 {@link #targetCache} 的话，
     * 每次未命中都要重跑一遍完整判定（阶段 1 一个方块 ~200 ns），6 次 × 4096 个方块就是毫秒级；
     * 有了这张表，重复查询退化成一次哈希查找。
     * <p>
     * 存真实方块而不是只存 key，理由与 {@link Entry#real} 相同：<b>"这里没有幽灵"这条结论同样是
     * 真实方块的函数</b>。方块一换，它可能就有了幽灵（或反过来），照旧返回"没有"就会让网格与剔除判据
     * 对不上——那正是 §13.8 那个"透过玻璃看进方块内部"的形状。
     * <p>
     * 与 {@link #targetCache} 一起在周期边界 / 标签变化 / 观测者状态变化时整体清空。
     */
    private final ConcurrentHashMap<Long, BlockState> evaluated = new ConcurrentHashMap<>();
    /**
     * 因为"真实方块变了"而作废的缓存条目数（自上个周期边界起）。
     * <p>
     * 诊断用：这个数在正常游玩里应该持续上涨——玩家挖掉的每一个有幽灵的方块、别人放的每一块砖
     * 都会让它 +1。它长期为 0 就说明有效期判据根本没生效（那正是"挖出来的洞 1~3 秒后才出现"的成因）。
     * 写在编译线程上，所以用 {@link java.util.concurrent.atomic.LongAdder}。
     */
    private final LongAdder staleInvalidations = new LongAdder();
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
    /** 调试时钟（由 SyncWorldDataPacket 同步）：失焦刻的流速倍率与偏移。 */
    private volatile double clockSpeed = 1.0;
    private volatile long clockOffset;
    /**
     * 服务端的{@link MutationSettings 解析输入快照}（由 {@code SyncMutationSettingsPacket} 同步）。
     * <p>
     * <b>这是"两端算得一样"的全部依据</b>：以前客户端自己从本端配置取参数、从集成服务器取种子，
     * 多人模式下种子拿不到就退回 {@code 0}，于是房主和别人看到的方块不是同一个东西。
     * 现在客户端<b>只</b>用服务端发来的这一份；没收到之前是 {@code null}，
     * 所有预览路径直接返回原方块——不显示是可见的、能诊断的，显示错了才是真 bug。
     * <p>
     * 编译线程要读它（{@code SectionCompiler} 路径），所以必须是 volatile。
     */
    private volatile MutationSettings mutationSettings;
    /** 快照未到的持续刻数（只为"等太久就报一次警"服务，见 {@link #tick()}）。 */
    private int missingSettingsTicks;
    /** 快照缺席多久之后报警（刻）。5 秒：正常情况下一两刻就到了，超过它一定是出事了。 */
    private static final int MISSING_SETTINGS_WARN_TICKS = 100;
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
        MutationSettings settings = this.mutationSettings;
        if (settings == null) {
            return original; // 服务端快照未到：不渲染任何幽灵（见 mutationSettings 的说明）
        }
        long key = pos.asLong();
        if (isProtected(pos, original, currentStage())) {
            evaluate(key, original);
            return original;
        }
        if (observerOnline) {
            evaluate(key, original);
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
            evaluate(key, original);
            return original;
        }

        long period = settings.displayPeriod(clientLevel.getGameTime(), clockSpeed, clockOffset);
        Entry entry = targetCache.get(key);
        if (entry != null && entry.validFor(original, period)) {
            return entry.state;
        }
        if (entry != null) {
            dropEntry(pos, key, entry, original);
        }

        BlockState target = computeTarget(clientLevel, pos, original, index, resolveGuidedModels(), settings);
        if (target == original || !isRenderableTarget(target) || !isExposed(region, pos)) {
            evaluate(key, original);
            return original;
        }
        putEntry(pos, target, original, period);
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
     * <p>
     * <b>两个缓存都必须用"传入的真实方块"验证</b>（2026-09-17）：方块被挖掉时周期并没有变，
     * 只按周期判有效就会拿"这个位置是块石头"的旧结论去剔邻块的面——挖出来的洞要等下一轮表面扫描
     * （1~3 秒）才出现。这也是为什么这里不能只判周期。
     */
    public BlockState ghostState(RenderChunkRegion region, BlockPos pos, BlockState real) {
        long key = pos.asLong();
        Entry entry = targetCache.get(key);
        if (entry != null) {
            if (entry.validFor(real, lastPeriodIndex)) {
                return entry.state;
            }
            if (entry.real != real) {
                dropEntry(pos, key, entry, real);
            }
        }
        BlockState negative = evaluated.get(key);
        if (negative == real) {
            return real; // 本周期已经就这个真实方块判定过，确定没有幽灵
        }
        return resolve(region, pos, real);
    }

    /**
     * 中键选取（pick block）时使用的"可见状态"：
     * 优先返回缓存的幽灵目标；未命中则按当前周期现算。
     */
    public BlockState visibleState(ClientLevel level, BlockPos pos) {
        BlockState original = level.getBlockState(pos);
        MutationSettings settings = this.mutationSettings;
        if (settings == null) {
            return original;
        }
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
        long period = settings.displayPeriod(level.getGameTime(), clockSpeed, clockOffset);
        Entry entry = targetCache.get(key);
        if (entry != null && entry.validFor(original, period)) {
            return entry.state;
        }
        BlockState target = computeTarget(level, pos, original, index, resolveGuidedModels(), settings);
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
        MutationSettings settings = this.mutationSettings;
        if (settings == null) {
            return original;
        }
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
        long period = settings.displayPeriod(level.getGameTime(), clockSpeed, clockOffset);
        Entry entry = targetCache.get(key);
        if (entry != null) {
            if (entry.validFor(original, period)) {
                return entry.state;
            }
            dropEntry(pos, key, entry, original);
        }
        BlockState target = computeTarget(level, pos, original, index, resolveGuidedModels(), settings);
        if (target == original || !isRenderableTarget(target) || !isExposed(level, pos)) {
            return original;
        }
        putEntry(pos, target, original, period);
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
            // 快照跟着连接走：断开后必须丢掉，否则上一个服务器的种子/配置会渗进下一个世界
            mutationSettings = null;
            missingSettingsTicks = 0;
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

        MutationSettings settings = this.mutationSettings;
        if (settings == null) {
            // 快照没到（登录包还在路上）：没有幽灵可清，也不该按本端配置猜一个显示出来。
            // 正常情况下它比第一批区块编译还早到，等 5 秒还没来就要出声——否则"失焦看不见了"
            // 会变成一个没有任何线索的现象（最可能的原因：服务端装的是旧版本 mod）。
            if (++missingSettingsTicks == MISSING_SETTINGS_WARN_TICKS) {
                LOGGER.warn("Focal Decay: still waiting for the server's mutation settings after {}s -"
                                + " defocus preview stays off until they arrive"
                                + " (is the server running an older version of the mod?)",
                        MISSING_SETTINGS_WARN_TICKS / 20);
            }
            return;
        }
        long period = settings.displayPeriod(current.getGameTime(), clockSpeed, clockOffset);
        if (period != lastPeriodIndex) {
            int sections = activeSections.size();
            int entries = targetCache.size();
            long stale = staleInvalidations.sumThenReset();
            lastPeriodIndex = period;
            clearCache();
            if (sections > 0 || entries > 0) {
                // stale = 本周期里"因为真实方块变了"而中途作废的幽灵数（玩家挖掉/放下了方块）。
                // 它长期为 0 意味着有效期判据失效——那正是"挖出来的洞 1~3 秒后才出现"的成因。
                LOGGER.info("Focal Decay: period {} - cleared {} ghost entries in {} sections"
                                + " ({} dropped mid-period because the real block changed), scheduled recompile",
                        period, entries, sections, stale);
            }
        }

        if (--scanCooldown <= 0) {
            scanCooldown = Math.max(1, FocalDecayConfig.SURFACE_UPDATE_FREQUENCY.get());
            scanSurfaces(current, settings);
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

    /** 服务端同步的末日天数、观测者状态与调试时钟；变化会清缓存重算。 */
    public void setWorldData(long days, boolean observerOnline, double clockSpeed, long clockOffset) {
        boolean changed = this.worldDays != days || this.observerOnline != observerOnline
                || this.clockSpeed != clockSpeed || this.clockOffset != clockOffset;
        if (changed) {
            this.worldDays = days;
            this.observerOnline = observerOnline;
            this.clockSpeed = clockSpeed;
            this.clockOffset = clockOffset;
            clearCache();
        }
    }

    /**
     * 收到服务端的解析输入快照（世界种子 + SERVER 配置）。
     * <p>
     * 这是客户端预览的<b>唯一</b>输入来源：快照一到就作废全部旧幽灵并重算
     * （种子/周期长度/概率都可能变了），之后 {@code resolve} 才会开始产出预览。
     */
    public void setMutationSettings(MutationSettings settings) {
        if (settings == null) {
            return;
        }
        MutationSettings previous = this.mutationSettings;
        this.mutationSettings = settings;
        missingSettingsTicks = 0;
        clearCache();
        lastPeriodIndex = Long.MIN_VALUE;
        if (previous == null) {
            LOGGER.info("Focal Decay: server mutation settings received (seed={} interval={} wildChance={} stage2Day={} stage3Day={})"
                            + " - defocus preview enabled",
                    settings.worldSeed(), settings.baseInterval(), settings.wildChance(),
                    settings.stage2Day(), settings.stage3Day());
        } else if (!previous.equals(settings)) {
            LOGGER.info("Focal Decay: server mutation settings changed - ghost cache dropped");
        }
    }

    /** 服务端同步下来的解析输入快照；未同步时为 {@code null}（此时不渲染任何幽灵）。 */
    public MutationSettings mutationSettings() {
        return mutationSettings;
    }

    /**
     * 左键/右键时把"我这一眼用的是哪个显示刻"回报给服务端
     * （{@link com.zhizhiwang.focal_decay.network.SyncClientViewPacket}）。
     * <p>
     * 只在真的有预览可谈的时候报：没有快照、没有世界、或失焦已终止（观测者在线）时，
     * 客户端与服务端本来就会得出同一个结论，报过去也只是噪音。
     * <p>
     * 返回的周期与 {@link #computeTarget} 用的是同一个表达式——报的必须是"真的用来看的那一个"，
     * 否则这个机制就只是在骗自己。
     */
    public void reportClientView(BlockPos pos) {
        MutationSettings settings = mutationSettings;
        ClientLevel current = level;
        if (settings == null || current == null || observerOnline) {
            return;
        }
        long period = settings.displayPeriod(current.getGameTime(), clockSpeed, clockOffset);
        PacketDistributor.sendToServer(new SyncClientViewPacket(pos.asLong(), period));
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
            prototypeList.add(toClientPrototype(p));
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
     * 收到<b>单条</b>原型机效果的增删改（{@link com.zhizhiwang.focal_decay.network.SyncPrototypePacket}）。
     * <p>
     * <b>为什么必须有这条通道</b>：有效原型机列表不落盘，靠方块实体的 {@code onLoad} 重建，
     * 所以登录时发出的整表<b>只包含当时已加载区块里的原型机</b>。玩家走到远处某个原型机旁边时，
     * 服务端开始保护那片区域，客户端却一直以为没人保护、继续画幽灵——挖下去得到的自然是原方块的掉落。
     * <p>
     * 与诞生周期增量同一个套路：只动受影响的那几个位置，不做整表扫描。
     */
    public void applyPrototype(ResourceKey<Level> dimension, long packedPos, boolean present,
                               SyncRegionDataPacket.PrototypeData data) {
        // 整表还没到就先建一份空的：增量与整表的到达顺序不保证（区块加载发生在登录流程中），
        // 丢掉一条增量就等于"客户端永远少知道一个保护范围"。整表到达时会整体替换，不会残留。
        RegionData old = regionData.computeIfAbsent(dimension, key -> new RegionData(List.of(), Map.of()));
        BlockPos pos = BlockPos.of(packedPos);
        List<ClientPrototype> prototypes = new ArrayList<>(old.prototypes.size() + 1);
        for (ClientPrototype prototype : old.prototypes) {
            if (!prototype.center().equals(pos)) {
                prototypes.add(prototype);
            }
        }
        if (present && data != null) {
            prototypes.add(toClientPrototype(data));
        }
        regionData.put(dimension, new RegionData(prototypes, old.birthPeriods));
        // 保护范围可能变了：把缓存里"现在被保护"的位置全部撤销。多删无害（下一轮扫描会补回来），
        // 少删就是"客户端留着服务端已经不认的幽灵"。
        refreshRegionData(Set.of());
    }

    private static ClientPrototype toClientPrototype(SyncRegionDataPacket.PrototypeData p) {
        return new ClientPrototype(BlockPos.of(p.pos()), p.radius(), p.type(),
                parseBlocks(p.trainedTargets()), Set.copyOf(p.trainedEntities()), p.bioActive(),
                p.concept(), p.progress(), p.q(), p.copies());
    }

    /**
     * 收到<b>单条</b>诞生周期变化（{@link com.zhizhiwang.focal_decay.network.SyncBirthPeriodPacket}）。
     * {@code period < 0} 表示删除。放置/破坏/交互转换都只发这一条，
     * 取代了旧实现"每次变化都重发整张表"的做法。
     */
    public void applyBirthPeriod(ResourceKey<Level> dimension, long packedPos, long period) {
        // 与 applyPrototype 同理：增量可能比整表先到（另一位玩家在你登录的同一刻放了方块），
        // 丢掉它会让客户端把一个"刚被放下的方块"当成世界原生方块，从而显示一个服务端不会执行的目标。
        RegionData old = regionData.computeIfAbsent(dimension, key -> new RegionData(List.of(), Map.of()));
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
     * <p>
     * 用的两个配置量（阶段3锁定强度、候选体训练点数）取自服务端快照：它们直接决定"有没有保护"，
     * 两端取值不同就会出现"客户端以为有保护、服务端照样转换"。
     */
    public MutationHelper.Protection protectionInfo(BlockPos pos, BlockState state, int stage) {
        RegionData data = currentRegionData();
        MutationSettings settings = this.mutationSettings;
        if (data == null || settings == null) {
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
            if (ObserverModelData.TYPE_BIO.equals(prototype.type()) && prototype.bioActive()) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_CANDIDATE.equals(prototype.type())
                    && prototype.progress() >= settings.requiredCandidatePoints(prototype.copies())) {
                return MutationHelper.Protection.HARD; // 已完成候选 = 完全稳定
            }
            if (ObserverModelData.TYPE_SEMANTIC_LOCK.equals(prototype.type())) {
                if (!prototype.trainedBlocks().contains(state.getBlock())) {
                    continue;
                }
                if (stage >= 3) {
                    double strength = settings.semanticLockStage3() * prototype.q();
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

    private void scanSurfaces(ClientLevel level, MutationSettings settings) {
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
            if (scanSection(level, sectionKey, settings)) {
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
    private boolean scanSection(ClientLevel level, long sectionKey, MutationSettings settings) {
        SectionPos section = SectionPos.of(sectionKey);
        BlockPos min = section.origin();
        long period = settings.displayPeriod(level.getGameTime(), clockSpeed, clockOffset);
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
                        if (removeEntry(pos, key, state)) {
                            changed = true;
                        }
                        continue;
                    }
                    evaluate(key, state);

                    BlockState target = computeTarget(level, pos, state, index, guided, settings);
                    if (target == state || !isRenderableTarget(target)) {
                        if (removeEntry(pos, key, state)) {
                            changed = true;
                        }
                        continue;
                    }

                    Entry prev = targetCache.put(key, new Entry(target, state, period));
                    if (prev == null || !prev.validFor(state, period) || prev.state != target) {
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
     * 目标计算。与服务端 {@code MutationTargets#resolveServer} 是同一个函数、同一组公式，
     * <b>同一份输入</b>（{@link MutationSettings} 由服务端同步而来）；
     * 差别只在上下文怎么装配（客户端从同步过来的区域数据里取保护与引导）。
     *
     * @param index    本次扫描/查询所用的突变查表（一次扫描只取一次，不要逐方块去取）
     * @param guided   本次扫描预解析的引导模型（见 {@link #resolveGuidedModels()}）
     * @param settings 服务端的解析输入快照
     */
    private BlockState computeTarget(ClientLevel level, BlockPos pos, BlockState original,
                                     MutationIndex index, List<GuidedModel> guided, MutationSettings settings) {
        if (observerOnline) {
            return original;
        }
        int stage = settings.stage(worldDays);
        return MutationHelper.resolve(original, pos, settings, stage,
                settings.displayPeriod(level.getGameTime(), clockSpeed, clockOffset), index,
                guidedBias(guided, pos, original, stage, settings.guidedStage3Halve()),
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
     * <p>
     * q 相等时按<b>中心坐标</b>决胜而不是按列表顺序：增量同步之后两端的列表顺序不保证一致
     * （服务端是登记顺序，客户端是"快照 + 增量到达顺序"），而 q 完全相等在同类模型上很常见
     * （两个同概念的引导模型）。只比 q 的话，同一格在两台机器上会抽到不同的概念池。
     */
    private static GuidedBias guidedBias(List<GuidedModel> models, BlockPos pos, BlockState original,
                                         int stage, boolean halveStage3) {
        GuidedModel best = null;
        double bestQ = 0.0;
        for (GuidedModel model : models) {
            if (!withinRadius(pos, model) || !model.pool().contains(original.getBlock())) {
                continue;
            }
            double q = GuidedConcept.effectiveQ(model.strength(), stage, halveStage3);
            if (!GuidedConcept.betterGuided(q, model.center(), bestQ, best == null ? null : best.center())) {
                continue;
            }
            bestQ = q;
            best = model;
        }
        return best == null ? GuidedBias.NONE : new GuidedBias(best.pool(), bestQ);
    }

    private int currentStage() {
        MutationSettings settings = mutationSettings;
        return settings == null ? 1 : settings.stage(worldDays);
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

    private void putEntry(BlockPos pos, BlockState target, BlockState real, long period) {
        long key = pos.asLong();
        Entry prev = targetCache.put(key, new Entry(target, real, period));
        if (prev == null) {
            incrSection(pos);
        }
        evaluated.put(key, real);
    }

    /** 记下"这个位置在真实方块 {@code real} 下没有幽灵"（负缓存）。 */
    private void evaluate(long key, BlockState real) {
        evaluated.put(key, real);
    }

    /**
     * 撤销一个幽灵条目。<b>不动 {@link #evaluated}</b>：这个位置在当前真实方块下确实已经判定过了，
     * 把负缓存一起删掉只会让面剔除路径反复重跑完整判定。真正需要"重新判定"的场合
     * （保护范围变化、诞生周期变化）由 {@code refreshRegionData} / {@code refreshBirths}
     * 显式地把位置从 {@code evaluated} 里摘掉。
     *
     * @param real 当前位置的真实方块；条目记的是别的东西时，这就是"真实方块变了"的那一次作废
     */
    private boolean removeEntry(BlockPos pos, long key, BlockState real) {
        Entry entry = targetCache.remove(key);
        if (entry == null) {
            return false;
        }
        decrSection(pos);
        if (entry.real != real) {
            staleInvalidations.increment();
        }
        return true;
    }

    /**
     * 丢弃一条已经作废的条目：真实方块变了（周期没变），或者周期翻页时被编译线程撞上。
     * <p>
     * 为什么值得单独一个方法、还带计数：前者正是"挖掉一个失焦方块后，邻块朝它的那一面 1~3 秒不渲染"
     * 的成因——旧的判据只看周期，于是编译线程在重编译时读到的还是"这里是块石头"的旧结论，
     * 把邻块的面剔掉了；要等下一轮表面扫描把这条条目删掉再重编译，洞才出现。
     * 计数写在周期日志里，长期为 0 就说明判据没生效。
     *
     * @param real 当前位置的真实方块；与条目记录的不同才算"真实方块变了"
     */
    private void dropEntry(BlockPos pos, long key, Entry entry, BlockState real) {
        if (targetCache.remove(key, entry)) {
            decrSection(pos);
            if (entry.real != real) {
                staleInvalidations.increment();
            }
        }
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
