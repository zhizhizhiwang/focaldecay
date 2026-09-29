package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.mixin.client.LevelRendererAccessor;
import com.zhizhiwang.focal_decay.mixin.client.RenderChunkRegionAccessor;
import com.zhizhiwang.focal_decay.network.RequestRegionDataPacket;
import com.zhizhiwang.focal_decay.network.SyncClientViewPacket;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
import com.zhizhiwang.focal_decay.mutation.AnchorNormalizeProfiler;
import com.zhizhiwang.focal_decay.mutation.Catalysis;
import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
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
 *   <li>突变周期切换时清空缓存并让受影响区块重编译。</li>
 * </ul>
 * 另外两件事<b>不在这里</b>，各有独立的类：
 * <ul>
 *   <li>observer_veil 后处理 → {@link ObserverVeil}（本类只决定"该不该画"）；</li>
 *   <li>保护区域 / 引导模型的同步与查询 → {@link ClientRegionData}（本类只问结果）。</li>
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
     * 换了 {@code ClientLevel} 之后等多少 tick 才去请求区域数据（BACKLOG P0-5）。
     * <p>
     * 为什么要等：登录/换维度时服务端本来就会推一次整表，而客户端换 level 与那次推送之间
     * 有几十 tick 的往返。等一小会儿再问，可以避免每次进世界都白问一遍。
     * 20 tick（1 秒）足够覆盖正常往返，又不至于让玩家盯着一片没有保护提示的世界太久。
     */
    private static final int REGION_REQUEST_DELAY_TICKS = 20;
    /** 仍然没有数据时，每隔这么多 tick 再问一次（同时打一条警告）。 */
    private static final int REGION_REQUEST_REPEAT_TICKS = 100;

    /**
     * 取"当前线程 CPU 时间"用于扫描计时。直接复用 {@link AnchorNormalizeProfiler#cpuClockNanos()}，
     * 不另写一份：两处的数字要能直接对比（客户端每位置成本 vs 锚固化每位置成本），
     * 各写一份迟早会在口径上分叉。不支持时返回 −1，调用方按"量不到"处理。
     */
    private static long cpuClockNanos() {
        return AnchorNormalizeProfiler.cpuClockNanos();
    }

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

    /** pos.asLong() -> 突变目标（含所属周期，防止跨周期读到旧值）。 */
    private final ConcurrentHashMap<Long, Entry> targetCache = new ConcurrentHashMap<>();
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

    // ------------------------------------------------------------------
    // 扫描成本埋点（BACKLOG P1-5）
    // ------------------------------------------------------------------
    /**
     * 扫描成本的累计计数（自上次 {@code /focaldecay clientstats reset} 起）。
     * <p>
     * <b>为什么需要它</b>：{@code P1-5} 的验收标准是"给出某视距下每秒扫描位置数 / 单次扫描耗时"，
     * 而在此之前客户端这条路径<b>完全不可观测</b>——只能靠感觉说"大视距下有点卡"。
     * 埋点本身刻意做得极便宜（几个 {@link LongAdder#increment()} 与一次 {@code System.nanoTime()}），
     * 因为被测的就是这段代码的开销，量具不能比被测物还贵。
     * <p>
     * 全部写在<b>主线程</b>上（{@code scanSurfaces} 由 {@code GameRendererMixin} 的渲染路径调用），
     * 用 {@code LongAdder} 只是为了不被编译器优化掉、并且让读取端的竞态无所谓。
     */
    private final LongAdder scanSections = new LongAdder();
    /** 遍历过的方块位置数（每节 4096）。 */
    private final LongAdder scanPositions = new LongAdder();
    /** 其中真正进入 {@code computeTarget}（含 ≤128 周期回扫）的位置数——这才是贵的那部分。 */
    private final LongAdder scanResolves = new LongAdder();
    /** {@code scanSection} 的累计墙钟时间（纳秒）。 */
    private final LongAdder scanNanos = new LongAdder();
    /**
     * 扫描队列为空的 tick 数（<b>P1-5</b> 的核心证据）。
     * <p>
     * 旧实现下这个数**恒为 0**：队列一空就整体重建，扫描器从不空闲。
     * 现在它应当占据大部分 tick——静止不动时接近 100%，走动时随"新看到的体积"下降。
     * 这是"优化真的生效了"唯一可观测的判据（每 tick 的 {@code scanNanos} 看不出这件事）。
     */
    private final LongAdder idleTicks = new LongAdder();
    /** 因为"新进入视野"而入队的节数（P1-5：走动的代价应当与它成正比，而不是与总视野）。 */
    private final LongAdder sectionsAdded = new LongAdder();
    /** 因为"真实方块变了"而入队的节数（P1-5 的正确性来源，见 {@link #onBlockChanged}）。 */
    private final LongAdder sectionsRescanned = new LongAdder();

    /** SectionPos.asLong()：当前存在幽灵方块的节。 */
    private final Set<Long> activeSections = ConcurrentHashMap.newKeySet();
    /** 每节幽灵方块数量，保证 activeSections 精确回收。 */
    private final ConcurrentHashMap<Long, Integer> sectionCounts = new ConcurrentHashMap<>();
    /** 待扫描节队列（按到玩家距离升序）。 */
    private final Queue<Long> pendingSections = new ArrayDeque<>();
    /**
     * 已经排进过队列的节（<b>P1-5</b>）。
     * <p>
     * 这张表是"扫描器不要重复劳动"的关键：队列排空之后不再整体重建，
     * 而是只把<b>新进入视野</b>的节补进来。判断"新"就靠这个集合。
     * <p>
     * 与 {@link #pendingSections} 一起在 {@code clearCache()} 里清空
     * （那时所有幽灵都作废，必须重新扫一遍）。
     */
    private final Set<Long> queuedSections = ConcurrentHashMap.newKeySet();
    /**
     * 需要重新扫描的节：它们里面的<b>真实方块变了</b>（别的玩家挖/放、爆炸、流体）。
     * <p>
     * 为什么必须有它：幽灵是"真实方块的函数"，而真实方块的变化只有两种到达方式——
     * ① 玩家自己挖/放（走 {@code InteractionHandler}，那两边都会处理）；
     * ② <b>别人</b>改的、或爆炸/流体改的，客户端只会在 {@code ClientLevel#setBlock} 里看到。
     * 没有这条通知，一个已经扫描过的节里新出现的候选方块就<b>永远不会</b>被扫到，
     * 表现为"别人放了块玻璃在我旁边，但幽灵没更新"——所以{@code AccessibilityChangeMixin}
     * 把 {@code setBlock} 接到这里。
     * <p>
     * 与 {@link #queuedSections} 的区别：那张是"已经排过队"，这张是"需要再排一次"。
     */
    private final Set<Long> rescansNeeded = ConcurrentHashMap.newKeySet();
    /**
     * 本轮 {@link #addVisibleSections} 枚举到的节（<b>P1-5</b>）。
     * <p>
     * 存在的唯一理由：发现"上一轮在视野内、这一轮不在了"的节，并把它们从
     * {@link #queuedSections} 里<b>忘掉</b>。为什么必须忘：
     * <blockquote>
     * 区块离开视野后会被卸载。它重新加载时内容<b>可能已经变了</b>（服务端在这期间改过、
     * 或者干脆是别的存档内容）。如果 {@code queuedSections} 还记着"这一节扫过了"，
     * 它就<b>永远不会</b>被重新扫描 —— 表现为"走远再回来，那一带的幽灵没了"。
     * </blockquote>
     * 这正是"用一张永久记账代替每次重扫"这类优化最容易漏的地方：
     * <b>记账的有效期必须覆盖所有让它失效的事件，而"卸载/重载"是其中之一。</b>
     * <p>
     * 只在主线程读写（{@code addVisibleSections} 由 {@code scanSurfaces} 调用），
     * 用普通 {@code HashSet} 即可。
     */
    private final Set<Long> visibleLastPass = new HashSet<>();
    /** 各维度的保护区域镜像（由 SyncRegionData/Prototype/BirthPeriod 三个包共同维护）。 */
    private final ClientRegionData regions = new ClientRegionData();

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
    /**
     * 当前客户端所在维度（2026-09-25，BACKLOG `P1-4`）。
     * <p>
     * <b>为什么需要这样一个字段</b>：区域数据的查询（保护判定、诞生周期、引导模型）
     * 都要先回答"这是哪个维度"。原先那三处各自去读
     * {@code Minecraft.getInstance().level.dimension()}——两次读同一个字段的 check-then-act，
     * 而它们会被<b>区块编译线程</b>调用：主线程在两次读之间换世界/断线就会读到错维度甚至 NPE。
     * <p>
     * 现在维度由主线程在 {@link #tick()} 里维护一次（volatile 发布），
     * 查询方只读这一个值；编译路径还能更精确——它用"本次编译那个 {@code RenderChunkRegion}
     * 的 level 维度"（见 {@link #resolve}），完全不依赖任何全局字段。
     * <p>
     * 换世界时会有"一个 tick 的窗口"里这个值还是旧维度，而此刻 byDimension 已被清空、
     * 新维度的快照也还没到——两个方向的查询都只会得到"没有数据"，
     * 也就是"不保护、无幽灵预览"这个安全结果。
     */
    private volatile ResourceKey<Level> currentDimension;
    /**
     * 已连续多少个 tick 处于"当前维度没有区域数据"的状态（BACKLOG P0-5）。
     * 换 {@code ClientLevel}（同维度重生也会）之后镜像被清空，靠它决定何时向服务端请求重发。
     */
    private int missingRegionRequests;

    /** 失焦遮罩后处理。加载/动画/淡出都在它里面，本类只决定"该不该画"。 */
    private final ObserverVeil veil = new ObserverVeil();

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
        // 先确认这次编译属于我们正在处理的那个世界（BACKLOG P1-4）：把 region 校验提到最前面，
        // 既省掉对外来世界的无谓判定，也让下面的 clientLevel 成为本次调用<b>钉住</b>的实例——
        // 保护查询用它自己的维度，不再读任何全局字段。
        if (!(region instanceof RenderChunkRegionAccessor accessor)) {
            return original;
        }
        if (!(accessor.focaldecay$getLevel() instanceof ClientLevel clientLevel) || clientLevel != this.level) {
            return original;
        }

        long key = pos.asLong();
        if (isProtectedNow(clientLevel.dimension(), pos, original)) {
            evaluate(key, original);
            return original;
        }
        if (observerOnline) {
            evaluate(key, original);
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
     * 中键选取（pick block）时使用的"可见状态"：优先返回缓存的幽灵目标；未命中则按当前周期现算，
     * 但<b>不写回缓存</b>——它挑的是"看到的是哪个方块"，与"哪块地能挖"无关，不该替挖掘路径做决定。
     */
    public BlockState visibleState(ClientLevel level, BlockPos pos) {
        return liveTarget(level, pos, false);
    }

    /**
     * 挖掘进度用"可见目标"（2026-08-21）：与渲染预览同一公式；优先读缓存，未命中时计算并写回——
     * 保证挖掘每 tick 只是 O(1) 缓存查询，累积回退扫描只发生在周期边界。
     */
    public BlockState miningState(ClientLevel level, BlockPos pos) {
        return liveTarget(level, pos, true);
    }

    /**
     * 两条即时查询路径共用的判定链（{@link #resolve} 是区块编译期的那一条，判据略有不同：
     * 它走的是 {@code RenderChunkRegion} 的 3×3 副本）。
     * <p>
     * 差异只有一处，由 {@code writeBack} 控制，刻意保留而不是"顺手统一"：
     * <ul>
     *   <li>{@code writeBack=false}（pick block）：命中缓存就返回，未命中就地算一次即走，
     *       不写缓存、也不看暴露面——中键拿的是"方块是什么"，与它是否可见无关。</li>
     *   <li>{@code writeBack=true}（挖掘进度）：未命中时算完要过暴露面判据并写回缓存，
     *       否则每 tick 都要重跑一次完整判定（累积回退扫描上限 128 个周期）。</li>
     * </ul>
     */
    private BlockState liveTarget(ClientLevel level, BlockPos pos, boolean writeBack) {
        BlockState original = level.getBlockState(pos);
        MutationSettings settings = this.mutationSettings;
        if (settings == null || isProtectedNow(currentDimension, pos, original) || observerOnline || original.isAir()) {
            // 快照未到 / 硬保护 / 失焦已终止 / 空气（空气既拾不到也挖不出目标）
            return original;
        }
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
        if (writeBack && entry != null) {
            dropEntry(pos, key, entry, original);
        }
        BlockState target = computeTarget(level, pos, original, index, resolveGuidedModels(), settings);
        if (target == original || !isRenderableTarget(target)) {
            return original;
        }
        if (!writeBack) {
            return target;
        }
        if (!isExposed(level, pos)) {
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
            regions.clear();
            pendingSections.clear();
            worldDays = 0;
            observerOnline = false;
            level = null;
            currentDimension = null;
            // 快照跟着连接走：断开后必须丢掉，否则上一个服务器的种子/配置会渗进下一个世界
            mutationSettings = null;
            missingSettingsTicks = 0;
            missingRegionRequests = 0;
            lastPeriodIndex = Long.MIN_VALUE;
            return;
        }

        if (current != level) {
            level = current;
            clearCache();
            // 扫描队列也必须丢掉（BACKLOG P1-6 第 3 条）：它装的是"上一个世界还要扫哪些区块节"，
            // 换世界之后那些坐标属于另一个维度，扫过去既浪费时间又可能把结果写进新世界的缓存。
            // 队列原本只在排空后才重建，所以不清就会真的去扫旧坐标。
            pendingSections.clear();
            // 换世界/重生也丢掉区域镜像：维度键（尤其 minecraft:overworld）在不同存档里是同一个，
            // 留着上一个世界的数据会让新世界开局就带一批不存在的保护范围与诞生周期。
            //
            // ⚠️ 丢掉之后必须能重新拿到（BACKLOG P0-5）：这条路径不只覆盖"换存档"，
            // 也覆盖同维度死亡重生——那种情况下服务端不会重发，而镜像没了就再也回不来，
            // 整局游戏客户端都不知道哪里受保护。下面的 missingRegionRequests 负责补这一刀。
            regions.clear();
            lastIndex = null;
            lastPeriodIndex = Long.MIN_VALUE;
        }
        // 维度随 level 一起发布（volatile）：编译线程只读这一个值，
        // 不再各自去 Minecraft.getInstance().level 上做 check-then-act（BACKLOG P1-4）。
        currentDimension = current.dimension();

        // 自愈：换了 ClientLevel（重生/跨维度/换世界）之后，如果本维度还没有整表快照，
        // 就主动向服务端要一次。放在这里而不是依赖某个重生事件，是因为"客户端发现自己的数据没了"
        // 是事实，"服务端认为客户端该要数据了"是推断——以后任何新的丢镜像原因都被同一条逻辑覆盖。
        // 加延迟是为了不与登录/换维度时本来就有的那一次推送打架（重复请求一份整表不划算）。
        if (!regions.hasSnapshot(currentDimension)) {
            if (++missingRegionRequests == REGION_REQUEST_DELAY_TICKS) {
                PacketDistributor.sendToServer(new RequestRegionDataPacket());
            }
            if (missingRegionRequests % REGION_REQUEST_REPEAT_TICKS == 0) {
                LOGGER.warn("Focal Decay: still no region data for this dimension after {}s -"
                                + " protection and placed-block info are missing on the client"
                                + " (is the server running an older version of the mod?)",
                        missingRegionRequests / 20);
            }
        } else {
            missingRegionRequests = 0;
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
            int decisions = evaluated.size();
            long stale = staleInvalidations.sumThenReset();
            lastPeriodIndex = period;
            clearCache();
            if (sections > 0 || entries > 0) {
                // stale = 本周期里"因为真实方块变了"而中途作废的幽灵数（玩家挖掉/放下了方块）。
                // 它长期为 0 意味着有效期判据失效——那正是"挖出来的洞 1~3 秒后才出现"的成因。
                // decisions 是负缓存在清空前的条目数，用来盯住 BACKLOG P0-6 那类无界增长。
                LOGGER.info("Focal Decay: period {} - cleared {} ghost entries in {} sections"
                                + " ({} dropped mid-period because the real block changed,"
                                + " {} cached decisions), scheduled recompile",
                        period, entries, sections, stale, decisions);
            }
        }

        if (--scanCooldown <= 0) {
            scanCooldown = Math.max(1, FocalDecayConfig.SURFACE_UPDATE_FREQUENCY.get());
            scanSurfaces(current, settings);
        }
    }

    /**
     * 扫描成本的当前读数（{@code /focaldecay clientstats}）。
     * <p>
     * {@code cpuNanos} 与 {@code AnchorNormalizeProfiler} 同一个口径（线程 CPU 时间），
     * 所以客户端扫描与锚固化两边的每位置成本可以直接比。
     * {@code cpuNanos < 0} 表示本 JVM 不支持取线程 CPU 时间（那时只有墙钟可用）。
     */
    public record ScanStats(long sections, long positions, long resolves, long cpuNanos,
                            int ghostEntries, int ghostSections, int cachedDecisions,
                            int queuedSections, long staleInvalidations,
                            long idleTicks, long sectionsAdded, long sectionsRescanned) {

        /**
         * 扫描器空闲的 tick 占比（0~1）。
         * <p>
         * <b>这是 P1-5 的验收数字</b>：优化前恒为 0（队列一空就整体重建），
         * 优化后静止时应当接近 1。分母用"有幽灵的 tick"近似（{@code sections > 0} 自上次 reset 起），
         * 所以它是个粗指标——但它要回答的问题很粗："到底闲下来了没有"。
         */
        public double idleShare() {
            long busy = sections;                 // 每个被扫描的节 ≈ 一个 tick 的工作
            long total = busy + idleTicks;
            return total == 0 ? 0.0 : (double) idleTicks / total;
        }

        /** 平均每个位置的 CPU 纳秒。−1 表示量不到。 */
        public double nanosPerPosition() {
            return cpuNanos < 0 || positions == 0 ? -1.0 : (double) cpuNanos / positions;
        }

        /** 每个节 4096 个位置里真正进入回扫的比例。 */
        public double resolveShare() {
            return positions == 0 ? 0.0 : (double) resolves / positions;
        }
    }

    public ScanStats scanStats() {
        return new ScanStats(scanSections.sum(), scanPositions.sum(), scanResolves.sum(),
                scanNanos.sum(), targetCache.size(), activeSections.size(), evaluated.size(),
                pendingSections.size(), staleInvalidations.sum(),
                idleTicks.sum(), sectionsAdded.sum(), sectionsRescanned.sum());
    }

    /** 清掉扫描埋点（不动缓存本身）：让"调完设置后重新量一段"有意义。 */
    public void resetScanStats() {
        scanSections.reset();
        scanPositions.reset();
        scanResolves.reset();
        scanNanos.reset();
        idleTicks.reset();
        sectionsAdded.reset();
        sectionsRescanned.reset();
    }

    /**
     * 每帧渲染世界结束时调用（{@code GameRendererMixin}，在 {@code renderLevel} 的 TAIL）：
     * 决定要不要推进 observer_veil 后处理；真正的加载/动画/淡出在 {@link ObserverVeil}。
     *
     * @param frameDeltaTicks <b>每帧真实时间</b>增量（{@code DeltaTracker#getRealtimeDeltaTicks}），
     *                        不是 {@code getGameTimeDeltaTicks}，单位是刻。理由见 {@link ObserverVeil}。
     */
    public void updateVeil(float frameDeltaTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (!FocalDecayConfig.POST_PROCESS_ENABLED.get()
                || mc.level == null
                || mc.gameRenderer.currentEffect() != null) {
            // 别的后处理效果占用时也让位：两条 PostChain 会互相踩渲染目标
            veil.close();
            return;
        }
        veil.update(frameDeltaTicks, observerOnline);
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
        // 池成员也来自这份快照（BACKLOG P0-7）：构建索引是两端共用的同一段代码，
        // 取值来源必须与其它输入一致，否则服务端与客户端会各自建出不同的池。
        MutationIndexes.setWildAutoInclude(settings.wildAutoInclude());
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
        // 整表到达 = 登录/换维度/原型机整体变化：诞生周期表可能整片变了，先把差异算出来。
        Map<BlockPos, Long> incoming = new HashMap<>(birthPositions.length);
        for (int i = 0; i < birthPositions.length; i++) {
            incoming.put(BlockPos.of(birthPositions[i]), birthPeriods[i]);
        }
        Set<BlockPos> changedBirths = regions.applySnapshot(dimension, prototypes, incoming);
        // 保护范围与诞生周期都可能变了：把受影响的幽灵连同"此处无幽灵"的负缓存一起丢掉。
        // 多删无害（下一轮扫描会补回来），少删就是"客户端留着服务端已经不认的幽灵"。
        dropRegionEntries(changedBirths);
    }

    /**
     * 收到<b>单条</b>原型机效果的增删改（{@link com.zhizhiwang.focal_decay.network.SyncPrototypePacket}）。
     * <p>
     * 有效原型机列表不落盘、靠方块实体的 {@code onLoad} 重建，所以整表可能不含远处的原型机；
     * 这条增量通道把它补上（细节见 {@link ClientRegionData#applyPrototype}）。
     * 与诞生周期增量同一个套路：只动受影响的地方，不做整表扫描。
     */
    public void applyPrototype(ResourceKey<Level> dimension, long packedPos, boolean present,
                               SyncRegionDataPacket.PrototypeData data) {
        regions.applyPrototype(dimension, packedPos, present, data);
        dropRegionEntries(Set.of());
    }

    /**
     * 收到<b>单条</b>诞生周期变化（{@link com.zhizhiwang.focal_decay.network.SyncBirthPeriodPacket}）。
     * {@code period < 0} 表示删除。放置/破坏/交互转换都只发这一条，
     * 取代了旧实现"每次变化都重发整张表"的做法。
     * <p>
     * 只清理这个位置，不做整表扫描：放置/破坏方块是高频操作，而整表扫描是 O(幽灵数) 的。
     */
    /**
     * 收到<b>单条</b>催化域变化（{@code until < 0} 表示清除）。
     * <p>
     * 与诞生周期一样属于"解析输入变了"，所以受影响的幽灵要丢掉重画；
     * 但催化改变的是整片区域的"是否发生"，所以整区重扫由周期边界与扫描器自然接手——
     * 这里只负责让镜像跟上，别让两端算出不同的世界。
     */
    public void applyCatalystField(ResourceKey<Level> dimension, long packedPos, int radius, int ringWidth,
                                   long until, double spill, String concept, double q) {
        BlockPos pos = BlockPos.of(packedPos);
        regions.applyCatalystField(dimension, pos,
                new Catalysis.Field(pos, radius, ringWidth, until, spill, concept, q));
    }

    public void applyBirthPeriod(ResourceKey<Level> dimension, long packedPos, long period) {
        BlockPos pos = BlockPos.of(packedPos);
        regions.applyBirthPeriod(dimension, pos, period);
        dropRegionEntries(Set.of(pos));
    }

    /**
     * 丢掉"现在受硬保护"或"诞生周期刚变过"的位置上的幽灵，并清掉它们的负缓存。
     * 三个同步入口共用这一段。
     * <p>
     * <b>负缓存也要清</b>：诞生周期通过 {@code MutationHelper} 的 {@code fromPeriod} 门控
     * 直接参与解析，所以"这里没有幽灵"这条旧结论在诞生/保护变化之后同样失效。
     *
     * @param changedBirths 诞生状态已知变化的位置；只关心保护范围时传空集
     */
    private void dropRegionEntries(Set<BlockPos> changedBirths) {
        if (targetCache.isEmpty() && evaluated.isEmpty()) {
            return;
        }
        ClientLevel current = Minecraft.getInstance().level;
        MutationSettings settings = this.mutationSettings;
        int stage = currentStage();
        Set<Long> dirty = new HashSet<>();
        if (settings != null) {
            targetCache.forEach((key, entry) -> {
                BlockPos pos = BlockPos.of(key);
                BlockState real = current != null ? current.getBlockState(pos) : Blocks.AIR.defaultBlockState();
                if (regions.isProtected(currentDimension, pos, real, stage, settings)) {
                    targetCache.remove(key, entry);
                    decrSection(pos);
                    evaluated.remove(key);
                    dirty.add(SectionPos.asLong(pos));
                }
            });
        }
        for (BlockPos pos : changedBirths) {
            long key = pos.asLong();
            if (targetCache.remove(key) != null) {
                decrSection(pos);
                dirty.add(SectionPos.asLong(pos));
            }
            evaluated.remove(key);
        }
        for (long sectionKey : dirty) {
            markSectionDirty(SectionPos.of(sectionKey));
        }
    }

    /**
     * 该维度下这个位置是否受保护（渲染/扫描的早退判据）。
     * <p>
     * 维度由调用方传入（BACKLOG `P1-4`）：区块编译线程用"本次编译那个 level 的维度"，
     * 扫描与交互路径用 {@link #currentDimension}。这个方法因此不再依赖任何全局可变字段。
     */
    private boolean isProtectedNow(ResourceKey<Level> dimension, BlockPos pos, BlockState state) {
        MutationSettings settings = this.mutationSettings;
        return settings != null && regions.isProtected(dimension, pos, state, currentStage(), settings);
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
        // ⚠️ P1-5（2026-09-26）：这里**不再**"队列空了就整体重建"。
        //
        // 旧行为是 pendingSections 一空就 rebuildScanQueue（把视野内约 500 个节全部重新排队），
        // 于是扫描器**从不空闲**：实测 8 视距下约 2.11M 位置/秒、连续运行，
        // 而其中绝大多数格子与上一次扫描相比什么都没变。实测判读见
        // VERIFY-device-matrix.md §4.6，设计背景见 BACKLOG P1-5。
        //
        // 现在的分工：
        //   - **新进入视野**的节由 addVisibleSections 增量补入（每 tick 都调，很便宜：
        //     它只做 17×17 列 × 13 层的坐标枚举与集合查询，不做任何方块读取）；
        //   - **真实方块变过**的节由 rescansNeeded 补入（setBlock 通知）；
        //   - 其余情况扫描器**空闲**——那正是省下来的部分。
        BlockPos center = mc.player.blockPosition();
        addVisibleSections(level, center);

        // 把"需要重扫"的节并进队列。先移除再入队：这样即使它已经在队列里也不会重复。
        if (!rescansNeeded.isEmpty()) {
            for (long key : rescansNeeded) {
                if (queuedSections.add(key)) {
                    pendingSections.add(key);
                    sectionsRescanned.increment();
                }
            }
            rescansNeeded.clear();
        }

        if (pendingSections.isEmpty()) {
            // 空闲：本 tick 没有任何需要（重）扫的节。旧实现永远走不到这里。
            idleTicks.increment();
            return;
        }
        int budget = SCAN_SECTION_BUDGET;
        while (budget-- > 0 && !pendingSections.isEmpty()) {
            long sectionKey = pendingSections.poll();
            queuedSections.remove(sectionKey);
            if (scanSection(level, sectionKey, settings)) {
                markSectionDirty(SectionPos.of(sectionKey));
            }
        }
    }

    /**
     * 某个节里的真实方块变了（{@code ClientLevel#setBlock} 的通知口，见 P1-5）。
     * <p>
     * 只记"这个节需要重扫"，不做任何方块读取或缓存修改——它可能从网络线程被调用，
     * 而这个集合是并发的，{@code scanSurfaces} 会在主线程上把它并进队列。
     * <p>
     * <b>不在这里立刻丢弃缓存条目</b>：那条路要改 {@code targetCache}/活跃节计数，
     * 需要 {@code sectionLock}，而且会与编译线程抢锁。让扫描器处理更简单也更安全
     * （扫描器本来就会用 {@code entry.real != real} 把过期条目丢掉）。
     */
    public void onBlockChanged(BlockPos pos) {
        rescansNeeded.add(SectionPos.asLong(pos));
    }

    /** 重建扫描队列：加载范围内、视锥可见、非空区块节，按距离升序。 */
    /**
     * 把<b>新进入视野</b>的节补进扫描队列（<b>P1-5</b>：增量，不重建）。
     * <p>
     * 旧实现是 {@code rebuildScanQueue}：{@code pendingSections.clear()} 之后把视野内所有节
     * 重新排一遍。它每 ~2 秒发生一次（队列被排空的速度），于是扫描器从不空闲。
     * 现在改成增量：
     * <ul>
     *   <li>已经从队列出去过的节用 {@link #queuedSections} 记住，<b>不再重复入队</b>
     *       ——它的幽灵仍然有效（有效期的输入没变），重扫它是纯浪费；</li>
     *   <li>真正需要重扫的节走 {@link #rescansNeeded}（真实方块变了）与
     *       {@code clearCache()}（有效期输入变了）；</li>
     *   <li>相机移动只会让<b>新</b>的一圈节进来，于是每次移动的代价与"新看到的体积"成正比，
     *       而不是与"总视野"成正比。</li>
     * </ul>
     * <b>这个函数每 tick 都跑</b>，所以它必须便宜：只做坐标枚举与集合查询，
     * 唯一的方块侧调用是 {@code chunk.getSections()} 与 {@code hasOnlyAir()}。
     * 那比"每格读一次方块状态再判候选"便宜三四个数量级。
     */
    private void addVisibleSections(ClientLevel level, BlockPos center) {
        Frustum f = frustum;
        SectionPos centerSection = SectionPos.of(center);
        // 本轮枚举到的节。与上一轮比较，差集 = "离开了视野"的节（见 visibleLastPass 的说明）。
        Set<Long> visibleNow = new HashSet<>();
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
                    long key = sec.asLong();
                    visibleNow.add(key);
                    if (queuedSections.contains(key)) {
                        continue; // 已经排过队且仍然有效（见方法注释）：不重复入队
                    }
                    long ddx = sec.x() - centerSection.x();
                    long ddy = sec.y() - centerSection.y();
                    long ddz = sec.z() - centerSection.z();
                    entries.add(new long[]{key, ddx * ddx + ddy * ddy + ddz * ddz});
                }
            }
        }
        // 新增的节按"到玩家的距离"升序排：近距离的先扫，玩家的观感更好。
        // 已经排过的节不参与这次排序（它们在队列里的相对顺序保持原样），
        // 所以这里只排"这一轮新看到的那一圈"——数量很小，排序代价可以忽略。
        entries.sort(Comparator.comparingLong(e -> e[1]));
        for (long[] entry : entries) {
            if (queuedSections.add(entry[0])) {
                pendingSections.add(entry[0]);
                sectionsAdded.increment();
            }
        }

        // 忘掉已经离开视野的节。下一轮它们若重新进入视野（区块重载），会被当作"新节"重新排队
        // ——那一节的内容在卸载期间可能已经变了，必须重扫。见 visibleLastPass 的说明。
        if (!visibleLastPass.isEmpty()) {
            for (long key : visibleLastPass) {
                if (!visibleNow.contains(key)) {
                    queuedSections.remove(key);
                }
            }
        }
        visibleLastPass.clear();
        visibleLastPass.addAll(visibleNow);
    }

    /** 扫描一个区块节：只保留暴露面候选方块的目标缓存，返回是否有变化（需要重编译）。 */
    private boolean scanSection(ClientLevel level, long sectionKey, MutationSettings settings) {
        // 计时包住整个方法：它才是"单次扫描耗时"的定义（含 isExposed 的邻居查询与全部 resolve）。
        // cpuClockNanos 而不是 System.nanoTime：量的是这段代码自己的开销，
        // 与 AnchorNormalizeProfiler 用同一个口径，两个数字可以直接比。
        long startedAt = cpuClockNanos();
        try {
            return scanSectionBody(level, sectionKey, settings);
        } finally {
            scanSections.increment();
            scanNanos.add(cpuClockNanos() - startedAt);
        }
    }

    private boolean scanSectionBody(ClientLevel level, long sectionKey, MutationSettings settings) {
        SectionPos section = SectionPos.of(sectionKey);
        BlockPos min = section.origin();
        long period = settings.displayPeriod(level.getGameTime(), clockSpeed, clockOffset);
        int stage = currentStage();
        // 逐节取一次查表和引导模型：4096 个方块共用，循环体里不再有任何标签/字符串操作。
        MutationIndex index = MutationIndexes.get(level.dimension());
        List<ClientRegionData.GuidedModel> guided = resolveGuidedModels();
        boolean changed = false;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    scanPositions.increment();
                    BlockPos pos = min.offset(x, y, z);
                    long key = pos.asLong();
                    BlockState state = level.getBlockState(pos);

                    if (!isCandidate(state, index) || isProtectedNow(level.dimension(), pos, state) || !isExposed(level, pos)) {
                        if (removeEntry(pos, key, state)) {
                            changed = true;
                        }
                        continue;
                    }
                    evaluate(key, state);

                    scanResolves.increment();
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
                                     MutationIndex index, List<ClientRegionData.GuidedModel> guided,
                                     MutationSettings settings) {
        if (observerOnline) {
            return original;
        }
        int stage = settings.stage(worldDays);
        long period = settings.displayPeriod(level.getGameTime(), clockSpeed, clockOffset);
        Catalysis catalysis = regions.catalysis(level.dimension(), pos, period);
        // 与服务端逐字同一条规则：点火优先，域内跳过源门控；域外维持背景引导。
        GuidedBias bias = catalysis.forced()
                ? regions.catalystBias(level.dimension(), pos, period, index, original)
                : ClientRegionData.guidedBias(guided, pos, original, stage, settings.guidedStage3Halve());
        return MutationHelper.resolve(original, pos, settings, stage, period, index,
                bias,
                regions.protectionInfo(level.dimension(), pos, original, stage, settings),
                regions.blockBirthPeriod(level.dimension(), pos),
                catalysis);
    }

    /**
     * 把已同步的引导模型解析成"半径 + 概念池 + 强度"的可直接查询形态。
     * 每次扫描一个区块节解析一次：{@code MutationIndexes#tagged} 有缓存，
     * 但也没必要在每个方块上重复走一遍。
     * <p>
     * 解析与偏向公式都在 {@link ClientRegionData}——那里才是这份数据的来源。
     */
    private List<ClientRegionData.GuidedModel> resolveGuidedModels() {
        return regions.guidedModels(currentDimension);
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
     * （保护范围变化、诞生周期变化）由 {@code dropRegionEntries} 显式地把位置从 {@code evaluated} 里摘掉。
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

    /**
     * 区块节的成员计数与"活跃节"集合。两把锁分别保护各自的复合更新。
     * <p>
     * <b>为什么需要锁</b>（2026-09-25，BACKLOG `P1-6` 第 4 条）：这两个集合都被<b>多处</b>改
     * （主线程的扫描、编译线程的 {@code putEntry}/{@code dropEntry}、主线程的 {@code clearCache}），
     * 而原来每处都是"改计数 → 再改集合"两步。结果有两个可观测的坏处：
     * <ul>
     *   <li>幻影条目：{@code clearCache} 清空集合之后，一个在飞的编译线程又把计数加回去，
     *       于是集合里出现一条没有幽灵支撑的记录，导致多余的重编译；</li>
     *   <li>计数漂移：{@code decrSection} 在计数已经 ≤0 时删掉计数表项，但反方向的
     *       "先 put 再 incr" 让"同一条目被数了两次"成为可能（例如同 key 被 put 两次而
     *       {@code prev == null} 的判断被并发穿插）。</li>
     * </ul>
     * 锁的成本可以接受：{@code clearCache} 只在低频事件上调用（周期翻页、换世界、换查表、区域数据更新），
     * 逐方块路径上一次都不会碰到。用 {@code synchronized} 而不是更细的结构，
     * 是因为这里要的是"两个集合看到同一个状态"，而这正是原子块能表达、无锁结构很难表达的。
     */
    private final Object sectionLock = new Object();

    private void incrSection(BlockPos pos) {
        long section = SectionPos.asLong(pos);
        synchronized (sectionLock) {
            int count = sectionCounts.merge(section, 1, Integer::sum);
            if (count > 0) {
                activeSections.add(section);
            }
        }
    }

    private void decrSection(BlockPos pos) {
        long section = SectionPos.asLong(pos);
        synchronized (sectionLock) {
            int count = sectionCounts.merge(section, -1, Integer::sum);
            if (count <= 0) {
                // 计数不该落到 ≤0（那样说明有人多减了），顺手清干净而不是留着漂移的负数。
                sectionCounts.remove(section);
                activeSections.remove(section);
            }
        }
    }

    /**
     * 清空缓存并在必要时让受影响区块节重编译。
     * <p>
     * <b>"要不要重编译"与"要不要丢弃判定"是两件事</b>（2026-09-25 修，BACKLOG `P0-6`）：
     * 早先在 {@code targetCache} 与 {@code activeSections} 都为空时直接 {@code return}，
     * 于是 {@link #evaluated}（负缓存）<b>永远清不掉</b>——而"两个表都为空"恰恰是
     * {@code observerOnline = true}（失焦终止、不再产出幽灵）时的常态，
     * 断线也走同一条路径。结果是这张表在整个 JVM 会话里单调增长：
     * 每个编译过的区块节约 4096 条，渲染距离 16 长时间游玩可达千万级，键还是装箱的 {@code Long}。
     * <p>
     * 现在的分工：<b>清表无条件做</b>（丢弃判定是正确性要求），
     * 只有"标脏重编译"在确实没有幽灵时跳过（那本来就是纯开销）。
     * <p>
     * 为什么可以无条件清而不心疼：{@code clearCache} 的调用点全都是低频事件
     * （tick 里的周期/世界/查表变化、区域数据与快照更新），不在逐方块路径上；
     * 而重建判定的代价只在下一轮表面扫描里摊开——那本来就是要做的工作。
     */
    private void clearCache() {
        synchronized (sectionLock) {
            if (!activeSections.isEmpty()) {
                for (long sectionKey : activeSections) {
                    markSectionDirty(SectionPos.of(sectionKey));
                }
            }
            targetCache.clear();
            evaluated.clear();
            activeSections.clear();
            sectionCounts.clear();
            // ⚠️ P1-5：队列也必须一起重置。
            // 这些调用点的语义是"所有幽灵都作废"（周期边界 / 标签变化 / 快照变化 / 换维度），
            // 而 queuedSections 记的是"已经扫过且有效"——作废之后那个前提不成立了，
            // 所以必须忘记它，让下一轮把整个视野重新排一遍（这是**正确性**要求，不是优化）。
            // pendingSections 也清掉：里面排着的节即将被重新加入，留着会重复。
            pendingSections.clear();
            queuedSections.clear();
            rescansNeeded.clear();
            visibleLastPass.clear(); // 同上：作废之后"上一轮视野"这个参照也失效了
        }
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
}
