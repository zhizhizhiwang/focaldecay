package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 训练目标收集（设计大纲 §4.2）：训练模式下，手持"训练中"模型右键世界方块/生物加入目标。
 */
public final class ModelTrainingHandler {

    private ModelTrainingHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!ObserverModelItem.isTraining(held)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState real = serverLevel.getBlockState(pos);
        // 训练"玩家看到的"方块：取确定性失焦目标（与客户端预览同公式）
        BlockState visible = visibleState(serverLevel, pos, real);
        if (visible.isAir() || visible.hasBlockEntity()) {
            return;
        }
        String id = BuiltInRegistries.BLOCK.getKey(visible.getBlock()).toString();
        if (addTarget(event.getEntity(), held, id,
                Component.translatable(visible.getBlock().getDescriptionId()), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!ObserverModelItem.isTraining(held)) {
            return;
        }
        Entity target = event.getTarget();
        if (target instanceof Player || target instanceof ItemEntity) {
            return;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        if (addTarget(event.getEntity(), held, id, target.getType().getDescription(), false)) {
            event.setCanceled(true);
        }
    }

    private static boolean addTarget(Player player, ItemStack held, String id, Component displayName, boolean block) {
        ObserverModelData data = ObserverModelItem.getData(held);
        if (data == null || !ObserverModelData.TYPE_TRAINING.equals(data.type())) {
            return false;
        }
        int limit = FocalDecayConfig.TRAINING_MAX_TARGETS.get();
        List<String> current = block ? data.trainedTargets() : data.trainedEntities();
        if (current.contains(id)) {
            return false; // 已记录，忽略
        }
        if (current.size() >= limit) {
            player.displayClientMessage(Component.translatable("message.focal_decay.training_limit"), true);
            return false;
        }
        List<String> updated = new ArrayList<>(current);
        updated.add(id);
        ObserverModelData newData = block
                ? new ObserverModelData(data.type(), updated, data.trainedEntities(),
                data.stabilityStrength(), data.concept(), data.bioEnergy(), data.totalStability())
                : new ObserverModelData(data.type(), data.trainedTargets(), updated,
                data.stabilityStrength(), data.concept(), data.bioEnergy(), data.totalStability());
        ObserverModelItem.setData(held, newData);
        // 立即同步手持物品到客户端（组件变化默认不会即时同步）
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
                    -2, 0, serverPlayer.getInventory().selected, held));
        }
        player.displayClientMessage(Component.translatable(
                block ? "message.focal_decay.training_target_block" : "message.focal_decay.training_target_entity",
                displayName), true);
        return true;
    }

    /** 服务端计算"可见目标"方块状态（与客户端预览同一确定性公式）。 */
    private static BlockState visibleState(ServerLevel level, BlockPos pos, BlockState real) {
        MutationPoolManager manager = MutationPoolManager.get(level);
        long days = FocalDecayWorldData.get(level.getServer()).getDays();
        int stage = MutationHelper.currentStage(days);
        long period = MutationHelper.blockPeriod(level.getGameTime());
        List<Block> pool = manager.getGlobalPool().snapshot();
        double chance = MutationHelper.mutationChance(stage);
        boolean protectedPos = manager.isProtected(pos, real);
        long birth = manager.getBlockBirthPeriod(pos);
        GuidedBias bias = manager.getGuidedBias(pos, real, stage);
        return MutationHelper.getVisibleTarget(real, pos, level.getSeed(), period, pool, chance,
                bias, protectedPos, birth);
    }
}
