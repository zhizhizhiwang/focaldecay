package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.MutationControllerBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.List;

/**
 * 服务端事件挂载（设计大纲 §10.1）：
 *  - 稳定锚放置/破坏 → 更新锚位置集合
 *  - 突变控制器放置/破坏 → 更新区域覆盖
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

        if (state.is(ModBlocks.STABLE_ANCHOR.get())) {
            // 放置时先把保护范围内所有方块转换为当前周期的失焦目标，再登记保护，
            // 避免"稳定了转换前的状态"
            convertAnchorRange(serverLevel, pos, manager);
            manager.addAnchor(pos);
            ModNetwork.sendRegionDataToDimension(serverLevel);
        } else if (state.is(ModBlocks.MUTATION_CONTROLLER.get())) {
            BlockEntity be = serverLevel.getBlockEntity(pos);
            if (be instanceof MutationControllerBlockEntity controller) {
                manager.addOverride(new RegionOverride(
                        pos,
                        controller.getRadius(),
                        controller.getTagExpression(),
                        serverLevel.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK)));
            }
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
        if (state.is(ModBlocks.STABLE_ANCHOR.get())) {
            manager.removeAnchor(pos);
            ModNetwork.sendRegionDataToDimension(serverLevel);
        } else if (state.is(ModBlocks.MUTATION_CONTROLLER.get())) {
            manager.removeOverrideAt(pos);
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
    private static void convertAnchorRange(ServerLevel level, BlockPos anchorPos, MutationPoolManager manager) {
        int radius = FocalDecayConfig.ANCHOR_RADIUS.get();
        long periodIndex = currentPeriodIndex(level);
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        long worldSeed = level.getSeed();
        double chance = MutationHelper.mutationChance(stage);

        BlockPos.betweenClosed(anchorPos.offset(-radius, -radius, -radius), anchorPos.offset(radius, radius, radius))
                .forEach(p -> {
                    if (p.equals(anchorPos)) {
                        return;
                    }
                    BlockState state = level.getBlockState(p);
                    if (!MutationHelper.isConversionSource(state, level, p, stage)) {
                        return;
                    }
                    List<Block> pool = manager.getEffectivePool(p, state);
                    long birthPeriod = manager.getBlockBirthPeriod(p);
                    BlockState target = MutationHelper.getVisibleTarget(
                            state, p, worldSeed, periodIndex, pool, chance, false, stage, birthPeriod);
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
