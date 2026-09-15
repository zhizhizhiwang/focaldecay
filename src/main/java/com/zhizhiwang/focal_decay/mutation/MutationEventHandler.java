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
            manager.updatePrototypeEffect(serverLevel, pos, model);
            ModNetwork.sendRegionDataToDimension(serverLevel);
        } else {
            // 记录玩家放置方块的诞生周期：从放置那一刻重新开始计算崩坏
            long period = currentPeriodIndex(serverLevel);
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
            manager.removePrototypeEffect(pos);
            ModNetwork.sendRegionDataToDimension(serverLevel);
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
            long period = currentPeriodIndex(level);
            if (period < MutationHelper.CUMULATIVE_SCAN_CAP) {
                continue; // 地平线还没过 0，没有任何记录可以删
            }
            MutationPoolManager.get(level).pruneBirthPeriods(period);
        }
    }

    /** 玩家登录时同步锚集合与覆盖数据（客户端渲染使用）。 */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendRegionData(player);
            ModNetwork.sendWorldData(player);
        }
    }

    /** 玩家切换维度后同步新维度的锚数据。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
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
        long periodIndex = currentPeriodIndex(level);
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

    /** 服务端当前周期的 periodIndex（与客户端预览同公式）。 */
    public static long currentPeriodIndex(ServerLevel level) {
        return MutationHelper.blockPeriod(level.getGameTime());
    }
}
