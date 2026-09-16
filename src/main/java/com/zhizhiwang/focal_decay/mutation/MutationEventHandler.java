package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
     * 将锚保护范围内的方块全部转换为"当前的失焦目标"（与生存破坏同一公式），
     * 然后才由调用方登记保护。
     * <p>
     * 成本提示：半径 32（完全稳定模型）对应 65³ ≈ 27 万个坐标，逐个读取+写入方块并发客户端更新，
     * 是一次实打实的一次性卡顿。可以用 {@code anchor_normalize_range=false} 关掉——
     * 关掉之后保护区内的方块保持原样（保护区本来也不会显示幽灵）。
     * 实现上把一切与坐标无关的量（查表、阶段、概率、种子）全部提到循环外，
     * 循环体内只剩一次数组查表和一次抽取。
     */
    public static void convertPrototypeRange(ServerLevel level, BlockPos anchorPos, MutationPoolManager manager, int radius) {
        if (!FocalDecayConfig.ANCHOR_NORMALIZE_RANGE.get()) {
            return;
        }
        if (FocalDecayWorldData.get(level.getServer()).isObserverOnline()) {
            return; // 失焦终止：无需固化
        }
        long periodIndex = displayPeriodIndex(level);
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        long worldSeed = level.getSeed();
        double chance = MutationHelper.mutationChance(stage);
        MutationIndex index = MutationIndexes.get(level.dimension());

        BlockPos.betweenClosed(anchorPos.offset(-radius, -radius, -radius), anchorPos.offset(radius, radius, radius))
                .forEach(p -> {
                    if (p.equals(anchorPos) || !level.isLoaded(p)) {
                        return;
                    }
                    BlockState state = level.getBlockState(p);
                    if (!index.isSource(state.getBlock())) {
                        return;
                    }
                    GuidedBias bias = manager.getGuidedBias(p, state, stage);
                    BlockState target = MutationHelper.resolve(state, p, worldSeed, periodIndex, index, chance,
                            bias, MutationHelper.Protection.NONE, manager.getBlockBirthPeriod(p));
                    if (target != state) {
                        level.setBlock(p, target, 3);
                    }
                });
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
