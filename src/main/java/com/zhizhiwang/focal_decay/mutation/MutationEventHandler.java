package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.MutationControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

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
            manager.addAnchor(pos);
        } else if (state.is(ModBlocks.MUTATION_CONTROLLER.get())) {
            BlockEntity be = serverLevel.getBlockEntity(pos);
            if (be instanceof MutationControllerBlockEntity controller) {
                manager.addOverride(new RegionOverride(
                        pos,
                        controller.getRadius(),
                        controller.getTagExpression(),
                        serverLevel.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK)));
            }
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

        if (state.is(ModBlocks.STABLE_ANCHOR.get())) {
            manager.removeAnchor(pos);
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
        // 网络同步后续实现
    }
}
