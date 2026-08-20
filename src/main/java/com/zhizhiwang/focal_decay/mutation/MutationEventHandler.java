package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.List;

/**
 * 服务端事件挂载（设计大纲 §10.1）：
 *  - 原型机放置/破坏 → 更新原型机位置集合（模型效果里程碑 3 接入）
 *  - 维度加载 → 加载全局池
 */
public class MutationEventHandler {

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
            // 先按模型实际半径固化范围（此时效果尚未登记，getEffectivePool 仍走全局池），再登记保护
            if (be != null && be.hasActiveModel()) {
                convertPrototypeRange(serverLevel, pos, manager,
                        MutationPoolManager.radiusFor(ObserverModelItem.getData(model)));
            }
            manager.updatePrototypeEffect(pos, model);
            ModNetwork.sendRegionDataToDimension(serverLevel);
        } else {
            // 记录玩家放置方块的诞生周期：从放置那一刻重新开始计算崩坏
            manager.setBlockBirthPeriod(pos, currentPeriodIndex(serverLevel));
            ModNetwork.sendRegionDataToDimension(serverLevel);
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
            ModNetwork.sendRegionDataToDimension(serverLevel);
        }
        if (state.is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            manager.removePrototypeEffect(pos);
            ModNetwork.sendRegionDataToDimension(serverLevel);
        }
    }

    /** 维度加载后从标签加载全局池。 */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            MutationPoolManager.get(serverLevel).reloadGlobalPool(serverLevel);
        }
    }

    /** 玩家登录时同步锚集合与覆盖数据（客户端渲染使用）。 */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendRegionData(player);
        }
    }

    /** 玩家切换维度后同步新维度的锚数据。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendRegionData(player);
        }
    }

    /**
     * 将锚保护范围内的方块全部转换为"当前的失焦目标"（与生存破坏同一公式），
     * 然后才由调用方登记保护。
     */
    public static void convertPrototypeRange(ServerLevel level, BlockPos anchorPos, MutationPoolManager manager, int radius) {
        long periodIndex = currentPeriodIndex(level);
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        long worldSeed = level.getSeed();
        double chance = MutationHelper.mutationChance(stage);

        BlockPos.betweenClosed(anchorPos.offset(-radius, -radius, -radius), anchorPos.offset(radius, radius, radius))
                .forEach(p -> {
                    if (p.equals(anchorPos) || !level.isLoaded(p)) {
                        return;
                    }
                    BlockState state = level.getBlockState(p);
                    if (!MutationHelper.isConversionSource(state, level, p, stage)) {
                        return;
                    }
                    List<Block> pool = manager.getEffectivePool(p, state);
                    long birthPeriod = manager.getBlockBirthPeriod(p);
                    BlockState target = MutationHelper.getVisibleTarget(
                            state, p, worldSeed, periodIndex, pool, chance, false, birthPeriod);
                    if (target != state) {
                        level.setBlock(p, target, 3);
                    }
                });
    }

    /** 服务端当前周期的 periodIndex（与客户端预览同公式）。 */
    private static long currentPeriodIndex(ServerLevel level) {
        long days = FocalDecayWorldData.get(level.getServer()).getDays();
        int stage = MutationHelper.currentStage(days);
        return MutationHelper.periodIndex(level.getGameTime(), MutationHelper.intervalForStage(stage));
    }
}
