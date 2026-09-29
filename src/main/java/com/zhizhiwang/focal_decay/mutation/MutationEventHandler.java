package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 服务端事件挂载（设计大纲 §10.1）：
 *  - 原型机放置/破坏 → 更新原型机效果（模型效果里程碑 3 接入）
 *  - 方块放置/破坏 → 维护玩家方块的诞生周期
 *  - 标签更新 → 丢弃并重建突变查表（数据包驱动的池/形态类在这里生效）
 *  - 周期性地剪枝诞生周期表
 */
public class MutationEventHandler {

    /** 诞生周期剪枝的检查间隔（tick）。 */
    private static final long BIRTH_PRUNE_INTERVAL = 6000L;
    private static long lastBirthPruneTick = 0;

    /**
     * 重生后补发区域数据的延迟（tick）。等客户端完成 {@code ClientLevel} 替换再发，
     * 否则会被客户端随后的"换世界清镜像"再清一次。详见 {@link #onPlayerRespawn}。
     */
    private static final int RESPAWN_RESYNC_DELAY_TICKS = 40;

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getPlacedBlock();
        MutationPoolManager manager = MutationPoolManager.get(serverLevel);

        if (state.is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            AnchorPrototypeBlockEntity be = serverLevel.getBlockEntity(pos) instanceof AnchorPrototypeBlockEntity b ? b : null;
            ItemStack model = be != null ? be.getModelStack() : ItemStack.EMPTY;
            // 先按模型实际半径固化范围（此时效果尚未登记，getGuidedBias 仍无引导），再登记保护
            if (be != null && be.hasActiveModel()) {
                convertPrototypeRange(serverLevel, pos, manager,
                        MutationPoolManager.radiusFor(ObserverModelItem.getData(model)));
            }
            // 登记时会自行广播单条增量；不再重发整张区域表（那张表还带着诞生周期）
            manager.updatePrototypeEffect(serverLevel, pos, model);
        } else {
            // 记录玩家放置方块的诞生周期：从放置那一刻重新开始计算崩坏（显示时钟，见 birthPeriodIndex）
            long period = birthPeriodIndex(serverLevel, InteractionHandler.recentClientPeriod(event.getEntity()));
            manager.setBlockBirthPeriod(pos, period);
            ModNetwork.sendBirthPeriod(serverLevel, pos, period);
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        MutationPoolManager manager = MutationPoolManager.get(serverLevel);

        if (manager.removeBlockBirthPeriod(pos)) {
            ModNetwork.sendBirthPeriod(serverLevel, pos, -1L);
        }
        if (state.is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            // removePrototypeEffect 自行广播删除增量
            manager.removePrototypeEffect(serverLevel, pos);
        }
    }

    /**
     * 标签更新（数据包重载 / 客户端收到标签同步）后丢弃突变查表缓存。
     * <p>
     * 这是"池、形态类、豁免全部由数据包决定"这条设计的落点：整套预计算索引在这里失效，
     * 下一次访问（服务端抽取或客户端扫描）自动按新标签重建，两端各自重建但输入完全相同，
     * 因此结果依旧一致。
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        MutationIndexes.invalidate();
        // 服务端手里那些已登记的原型机效果持有**登记期**算好的概念池，索引一换它们就过期了，
        // 而客户端每次从新索引重建 → 引导邻域两端不一致（BACKLOG P1-6 第 12 条）。
        // 客户端没有 MutationPoolManager（它是 SavedData），所以这里必须判断服务端实例是否存在：
        // 本事件在客户端也会触发（收到服务端的标签同步时）。
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            MutationPoolManager.get(level).refreshConceptPools();
        }
    }

    /** 周期性剪枝诞生周期表（详见 {@link MutationPoolManager#pruneBirthPeriods(long)}）。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long tick = event.getServer().getTickCount();
        if (tick - lastBirthPruneTick < BIRTH_PRUNE_INTERVAL) {
            return;
        }
        lastBirthPruneTick = tick;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // 剪枝地平线必须与诞生记录同域（显示时钟）：存储刻与显示刻在倍率≠1 时分道扬镳——
            // 用存储刻当地平线会在加速时永不删除（表无界增长），在减速时把刚放下的记录删掉
            // （那等于"所有方块立刻失焦"）。回扫上限本来就是按周期数算的，所以这里天然对齐。
            long period = displayPeriodIndex(level);
            if (period < MutationHelper.CUMULATIVE_SCAN_CAP) {
                continue; // 地平线还没过 0，没有任何记录可以删
            }
            MutationPoolManager.get(level).pruneBirthPeriods(period);
        }
    }

    /**
     * 玩家登录时同步解析输入快照、锚集合与覆盖数据（客户端渲染使用）。
     * <p>
     * <b>顺序有讲究</b>：{@link ModNetwork#sendMutationSettings} 必须发。
     * 它是"世界种子 + SERVER 配置"的唯一来源，客户端在收到它之前不渲染任何幽灵；
     * 以前多人模式下客户端拿不到种子就退回 {@code 0}，于是房主和别人看到的方块不是同一个东西。
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendMutationSettings(player);
            ModNetwork.sendRegionData(player);
            ModNetwork.sendWorldData(player);
        }
    }

    /** 玩家切换维度后同步新维度的锚数据（输入快照与世界状态不变，但重发无害且更稳）。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendMutationSettings(player);
            ModNetwork.sendRegionData(player);
            ModNetwork.sendWorldData(player);
        }
    }

    /**
     * 玩家重生：延迟几 tick 后补发区域与世界数据（BACKLOG `P0-5`）。
     * <p>
     * <b>为什么重生也要发</b>：客户端丢弃区域镜像的条件是"{@code ClientLevel} 实例被替换"，
     * 而同维度死亡重生<b>也会</b>换一个 level——镜像就此清空，可原来只有登录与换维度两条重发路径，
     * 于是整局游戏客户端都不知道哪里受保护、哪些方块是玩家放的。
     * <p>
     * <b>为什么是"延迟"而不是立即发</b>：立即发会被客户端随后的换 level 再清一次，等于白发。
     * 客户端那边还有一条"发现缺数据就主动请求"的自愈逻辑，两条互为兜底——
     * 服务端这条不依赖客户端配合，客户端那条不依赖服务端记得这个事件。
     * <p>
     * 换维度重生由 {@link #onPlayerChangedDimension} 覆盖，这里只管同维度重生（重发一次也不亏）。
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + RESPAWN_RESYNC_DELAY_TICKS,
                () -> {
                    // 这几 tick 里玩家可能又换了维度或断线，所以重新取一次并在发之前确认还在
                    if (player.hasDisconnected()) {
                        return;
                    }
                    ModNetwork.sendRegionData(player);
                    ModNetwork.sendWorldData(player);
                }));
    }

    /**
     * 固化一次的范围统计。返回值是<b>测试与诊断的接口</b>：让"保护有没有生效"变成可观测的数字，
     * 而不是靠"某个方块碰巧变没变"来推断（那取决于概率骰子，会写出不稳定的断言）。
     *
     * @param scanned          真正跑过解析的坐标数
     * @param changed          真的被改写的坐标数
     * @param skippedProtected 因为落在<b>既有</b>硬保护范围内而直接跳过的坐标数
     */
    public record NormalizeStats(long scanned, long changed, long skippedProtected) {
    }

    /**
     * <b>点火时把结果写进世界</b>（2026-09-29，作者裁定 #8(b)：火种应当真的改变这片地，
     * 而不是只当一扇"点火期间才看得见"的窗口）。
     * <p>
     * 与锚固化同一个套路（逐坐标、只动源方块、硬保护绝不改写、flag 3），差别只有两处：
     * <ul>
     *   <li>用 {@code forced} 的催化：这一次写入就是"必中"的那一次；</li>
     *   <li>用<b>域自己的概念</b>当引导（{@link Catalysis.Field#biasFor}，跳过源门控、优先取高一档），
     *       而不是在场原型机的背景引导——玩家点的是这一片，别处的模型不该插手。</li>
     * </ul>
     * 成本：催化半径默认取模型的 {@code prototype_radius}（默认 8）→ 17³ ≈ 4,900 个坐标，
     * 与锚固化实测的 4,879 扫描同量级（那次 66 ms 是半径 32 的 27 万坐标）。
     */
    public static NormalizeStats igniteCatalystRange(ServerLevel level, BlockPos center, int radius,
                                                     Catalysis.Field field) {
        if (FocalDecayWorldData.get(level.getServer()).isObserverOnline()) {
            return new NormalizeStats(0, 0, 0); // 失焦终止：无可写入
        }
        long periodIndex = displayPeriodIndex(level);
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        MutationSettings settings = MutationSettings.server(level.getSeed());
        MutationIndex index = MutationIndexes.get(level.dimension());
        MutationPoolManager manager = MutationPoolManager.get(level);
        // 点火那一次是<b>唯一</b>允许越级的场合（climb=true）：石头要能被解释成铁矿，
        // 这一档是火给的凭据，也是台阶链"一次火一档"的唯一来源。
        Catalysis catalysis = new Catalysis(true, 0.0, 0.0, true);

        long[] counters = new long[3];
        BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))
                .forEach(p -> {
                    if (p.equals(center) || !level.isLoaded(p)) {
                        return;
                    }
                    BlockState state = level.getBlockState(p);
                    if (!index.isSource(state.getBlock())) {
                        return;
                    }
                    MutationHelper.Protection protection = manager.protectionInfo(p, state, stage, settings);
                    if (protection.hard()) {
                        counters[2]++;
                        return;
                    }
                    counters[0]++;
                    GuidedBias bias = field.biasFor(index, state, true);
                    BlockState target = MutationHelper.resolve(state, p, settings, stage, periodIndex, index,
                            bias, protection, manager.getBlockBirthPeriod(p), catalysis);
                    if (target != state) {
                        level.setBlock(p, target, 3);
                        counters[1]++;
                    }
                });
        FocalDecay.LOGGER.info("[focal_decay] catalyst fired at {} r={}: scanned={} written={} protected={}",
                center.toShortString(), radius, counters[0], counters[1], counters[2]);
        return new NormalizeStats(counters[0], counters[1], counters[2]);
    }

    /**
     * 将锚保护范围内的方块全部转换为"当前的失焦目标"（与生存破坏同一公式），
     * 然后才由调用方登记保护。
     * <p>
     * 成本提示：半径 32（完全稳定模型）对应 65³ ≈ 27 万个坐标，逐个读取+写入方块并发客户端更新，
     * 是一次实打实的一次性卡顿。可以用 {@code anchor_normalize_range=false} 关掉——
     * 关掉之后保护区内的方块保持原样（保护区本来也不会显示幽灵）。
     * 实现上把一切与坐标无关的量（查表、阶段、概率、种子）全部提到循环外，
     * 循环体内只剩一次数组查表和一次抽取。
     * <p>
     * <b>必须尊重既有保护</b>（2026-09-25 修）：固化发生在<b>新效果登记之前</b>，所以此刻
     * {@code manager.protectionInfo} 返回的正是"其他既有原型机"的保护形态。此前这里硬传
     * {@link MutationHelper.Protection#NONE}，于是把新基座放进一个已存在的稳定场内部时，
     * 会把那个场里<b>本应冻结</b>的方块按当前失焦态重写——服务端方块变了、客户端仍按硬保护
     * 不显示幽灵，表现为可见的失配。
     * <p>
     * 耗时与规模由 {@link AnchorNormalizeProfiler} 记录（BACKLOG P0-1）：先有数字，再谈优化。
     */
    public static NormalizeStats convertPrototypeRange(ServerLevel level, BlockPos anchorPos,
                                                       MutationPoolManager manager, int radius) {
        if (!FocalDecayConfig.ANCHOR_NORMALIZE_RANGE.get()) {
            return new NormalizeStats(0, 0, 0);
        }
        if (FocalDecayWorldData.get(level.getServer()).isObserverOnline()) {
            return new NormalizeStats(0, 0, 0); // 失焦终止：无需固化
        }
        long startedAt = System.nanoTime();
        long cpuStartedAt = AnchorNormalizeProfiler.cpuClockNanos();

        long periodIndex = displayPeriodIndex(level);
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        long worldSeed = level.getSeed();
        MutationSettings settings = MutationSettings.server(worldSeed);
        double chance = settings.blockChance(stage);
        MutationIndex index = MutationIndexes.get(level.dimension());

        // 计数器用数组包一层：lambda 里要写，局部变量写不了。
        // [0] = scanned, [1] = changed, [2] = skippedProtected
        long[] counters = new long[3];

        BlockPos.betweenClosed(anchorPos.offset(-radius, -radius, -radius), anchorPos.offset(radius, radius, radius))
                .forEach(p -> {
                    if (p.equals(anchorPos) || !level.isLoaded(p)) {
                        return;
                    }
                    BlockState state = level.getBlockState(p);
                    if (!index.isSource(state.getBlock())) {
                        return;
                    }
                    // 既有原型机的保护形态（此刻表里还没有本次要登记的那个效果）。
                    // 硬保护命中的坐标绝不改写；软保护（阶段 3 语义锁定）按原语义参与解析。
                    MutationHelper.Protection protection = manager.protectionInfo(p, state, stage, settings);
                    if (protection.hard()) {
                        counters[2]++;
                        return;
                    }
                    counters[0]++;
                    GuidedBias bias = manager.getGuidedBias(p, state, stage);
                    BlockState target = MutationHelper.resolve(state, p, settings, stage, periodIndex, index,
                            bias, protection, manager.getBlockBirthPeriod(p));
                    if (target != state) {
                        level.setBlock(p, target, 3);
                        counters[1]++;
                    }
                });

        AnchorNormalizeProfiler.record(level, anchorPos, radius, counters[0], counters[1],
                System.nanoTime() - startedAt,
                AnchorNormalizeProfiler.cpuClockNanos() - cpuStartedAt);
        return new NormalizeStats(counters[0], counters[1], counters[2]);
    }

    /**
     * <b>存储时钟</b>：只跟真实 gameTime 走的周期，不受 {@code /focaldecay period} 影响。
     * <p>
     * <b>2026-09-17 起出生周期不再用它</b>（见 {@link #birthPeriodIndex}），现在只有自测拿它当参照物：
     * 它证明"默认档位（speed=1 / offset=0）下，调试时钟与真实时间轴逐位相同"。
     */
    public static long storagePeriodIndex(ServerLevel level) {
        return MutationHelper.blockPeriod(level.getGameTime());
    }

    /**
     * <b>显示时钟</b>：失焦解析用的周期，带调试倍率与偏移，也就是 {@code /focaldecay period} 拨的指针。
     * 锚固化范围、出生周期与客户端预览都用它——三者比的必须是同一根指针。
     */
    public static long displayPeriodIndex(ServerLevel level) {
        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        return MutationHelper.displayPeriod(level.getGameTime(),
                worldData.getClockSpeed(), worldData.getClockOffset());
    }

    /**
     * <b>诞生周期</b>（2026-09-17 修正）：放置/转换那一刻<b>玩家看到的那一根指针</b>。
     * <p>
     * <b>为什么不能再记存储时钟</b>：闸门（{@code MutationHelper#resolve} 里的
     * {@code periodIndex < birthPeriod + 1}）比的是<b>显示时钟</b>，而 {@code /focaldecay period speed}
     * 会让两者按倍率分道扬镳。倍率 7 时显示刻是存储刻的 7 倍，于是"刚放下/刚转换"的方块
     * 一上来就满足 {@code periodIndex >= birthPeriod + 1}——闸门形同不存在：
     * 放置的方块立刻失焦，右键长按还能把一个方块来回转换（每次点击都重新出生一次）。
     * <p>
     * <b>为什么取两端的较大值</b>：服务端与客户端的 gameTime 会漂（客户端本地自走、每 20 tick
     * 才被校准一次），倍率越高，同样的刻偏差折算出的周期差越大。取"两个读数里更晚的那个"，
     * 两边的闸门都只会晚开、不会早开——早开是可见的 bug，晚开只是多保护一个周期。
     *
     * @param clientPeriod 行动客户端回报的显示刻；没有回报时传 {@link Long#MIN_VALUE}
     */
    public static long birthPeriodIndex(ServerLevel level, long clientPeriod) {
        return Math.max(displayPeriodIndex(level), clientPeriod);
    }
}
