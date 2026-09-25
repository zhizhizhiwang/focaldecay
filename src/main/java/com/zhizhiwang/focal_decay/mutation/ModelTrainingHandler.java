package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
        if (!ObserverModelItem.isTraining(held) && !ObserverModelItem.isCandidate(held)) {
            return;
        }
        if (ObserverModelItem.isCompletedCandidate(held)) {
            return; // 已 100% 完成：不再收集目标
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
        if (addTarget(event.getEntity(), held, event.getHand(), id,
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
        if (!ObserverModelItem.isTraining(held) && !ObserverModelItem.isCandidate(held)) {
            return;
        }
        if (ObserverModelItem.isCompletedCandidate(held)) {
            return; // 已 100% 完成：不再收集目标
        }
        Entity target = event.getTarget();
        if (target instanceof Player || target instanceof ItemEntity) {
            return;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        if (addTarget(event.getEntity(), held, event.getHand(), id, target.getType().getDescription(), false)) {
            event.setCanceled(true);
        }
    }

    /**
     * 已完成的候选观测者模型：在末地右键（使用物品、不面向方块/实体）生成指向王座的
     * 密集短粒子束（约 6 格长，末端小爆发）+ 距离提示。
     * 未完成的候选模型仍走右键收集目标（方块/实体）。
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (event.getLevel().isClientSide) {
            return; // 服务端统一播粒子
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel) || serverLevel.dimension() != net.minecraft.world.level.Level.END) {
            return;
        }
        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!ObserverModelItem.isCompletedCandidate(held)) {
            return;
        }
        net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) event.getEntity();
        BlockPos throne = ThroneStructure.thronePos(serverLevel.getSeed());
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(throne).add(0.0, 1.0, 0.0);
        Vec3 dir = target.subtract(eye).normalize();
        double distance = eye.distanceTo(target);
        // 密集短光束：每 0.25 格 4 个粒子，约 6 格长，末端再爆发一簇
        for (int i = 0; i < 25; i++) {
            Vec3 p = eye.add(dir.scale(i * 0.25));
            serverLevel.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 4, 0.15, 0.15, 0.15, 0.01);
        }
        Vec3 tip = eye.add(dir.scale(6.0));
        serverLevel.sendParticles(ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 30, 0.8, 0.8, 0.8, 0.1);
        player.displayClientMessage(Component.translatable(
                "message.focal_decay.candidate_locate", (int) Math.round(distance)), true);
    }

    private static boolean addTarget(Player player, ItemStack held, InteractionHand hand,
                                     String id, Component displayName, boolean block) {
        ObserverModelData data = ObserverModelItem.getData(held);
        boolean candidate = data != null && ObserverModelData.TYPE_CANDIDATE.equals(data.type());
        if (data == null || (!ObserverModelData.TYPE_TRAINING.equals(data.type()) && !candidate)) {
            return false;
        }
        // 候选体有独立上限（BACKLOG P1-6 第 13 条）：它本来豁免训练上限是有意的
        // （要练满需要超过 64 条记录），但"豁免"不等于"无界"——一件物品的组件里
        // 塞进上千个 id 字符串会拖慢每一次 STREAM_CODEC 序列化。
        int limit = candidate
                ? FocalDecayConfig.CANDIDATE_MAX_TARGETS.get()
                : FocalDecayConfig.TRAINING_MAX_TARGETS.get();
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
        int progress = candidate ? data.progress() + 1 : data.progress();
        ObserverModelData newData = block
                ? new ObserverModelData(data.type(), updated, data.trainedEntities(),
                data.stabilityStrength(), data.concept(), progress, data.bioEnergy(), data.totalStability(),
                data.copies())
                : new ObserverModelData(data.type(), data.trainedTargets(), updated,
                data.stabilityStrength(), data.concept(), progress, data.bioEnergy(), data.totalStability(),
                data.copies());
        ObserverModelItem.setData(held, newData);
        // 立即同步手持物品到客户端（组件变化默认不会即时同步）
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
                    -2, 0, syncSlotFor(serverPlayer, hand), held));
        }
        player.displayClientMessage(Component.translatable(
                block ? "message.focal_decay.training_target_block" : "message.focal_decay.training_target_entity",
                displayName), true);
        return true;
    }

    /**
     * 手 → 同步槽位号（<b>纯函数</b>，供自测）。
     * <p>
     * 修的是 BACKLOG P1-6 第 14 条：重同步固定写 {@code inventory.selected}，
     * 而 {@code held} 取自 {@code event.getHand()}。主手时两者恰好相同，所以一直没被发现；
     * <b>副手训练</b>时客户端收到的是"主手槽被写入了副手那枚模型的组件"——
     * 表现为进度显示错位，而真正的副手组件要等下一次整份同步才更新。
     * <p>
     * 两个槽位号的来历（写清楚，免得下一个人以为它是魔法数）：
     * <ul>
     *   <li>{@code Inventory.SLOT_OFFHAND = 40}——原版玩家物品栏里副手的固定序号；</li>
     *   <li>{@code inventory.selected}——当前选中的快捷栏格（0~8），也就是主手那一格。</li>
     * </ul>
     * 注意这里用的是 {@code ClientboundContainerSetSlotPacket} 的 {@code -2}（玩家物品栏）窗口 id，
     * 槽位号必须按该窗口的编号来，不是菜单槽位。
     */
    public static int syncSlotFor(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND
                ? net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND
                : player.getInventory().selected;
    }

    /** 服务端计算"可见目标"方块状态（与客户端预览同一确定性公式）。 */
    private static BlockState visibleState(ServerLevel level, BlockPos pos, BlockState real) {
        // 统一入口：包含"转换源"判定（门/楼梯/栅栏等不完整方块不会被记录成突变目标）
        return MutationTargets.resolveServer(level, pos, real);
    }
}
