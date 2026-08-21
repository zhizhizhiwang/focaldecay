package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 观测者核心修复路径（设计大纲 §11）：
 *  - GUI 按钮触发安装：消耗"已完成的候选观测者模型"，播放开始特效，调度完成 tick；
 *  - 完成 tick 由 {@link com.zhizhiwang.focal_decay.block.ObserverCoreBlock#tick} 处理（powered=true + 失焦终止广播）；
 */
public final class ObserverCoreHandler {

    private ObserverCoreHandler() {
    }

    /** 玩家在 GUI 点击"安装"：校验已完成的候选观测者模型 → 消耗 → 播放开始特效 → 调度完成 tick。 */
    public static boolean tryActivate(ServerLevel level, BlockPos pos, ServerPlayer player) {
        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        if (worldData.isObserverOnline()
                || level.getBlockState(pos).getValue(BlockStateProperties.POWERED)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.core_already_online"), true);
            return false;
        }
        if (!consumeCompletedCandidate(player)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.core_need_candidate"), true);
            return false;
        }
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                120, 1.0, 2.0, 1.0, 0.2);
        level.scheduleTick(pos, level.getBlockState(pos).getBlock(),
                FocalDecayConfig.OBSERVER_CORE_ACTIVATION_TICKS.get());
        player.displayClientMessage(Component.translatable("message.focal_decay.core_activating"), true);
        return true;
    }

    private static boolean consumeCompletedCandidate(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ObserverModelItem.isCompletedCandidate(stack)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }
}
