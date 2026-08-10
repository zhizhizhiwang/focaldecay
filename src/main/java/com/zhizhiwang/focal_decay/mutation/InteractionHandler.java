package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.attachment.BreakData;
import com.zhizhiwang.focal_decay.attachment.ModAttachments;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;

/**
 * 交互与转换（设计大纲 §5）：
 *  - 挖掘开始：锁定目标方块状态与周期索引（BreakData attachment）
 *  - 方块破坏：取消默认掉落，将方块真实转换为目标状态并生成掉落
 */
public class InteractionHandler {

    /** 挖掘开始，记录锁定数据。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Player player = event.getEntity();
        if (player.isCreative()) {
            return; // 创造模式破坏保持原版行为，不执行转换
        }
        BlockPos pos = event.getPos();
        BlockState state = serverLevel.getBlockState(pos);

        // 带方块实体的方块、空气、黑名单：不参与转换
        if (state.isAir() || state.hasBlockEntity()
                || state.is(ModTags.Blocks.CONVERSION_BLACKLIST)) {
            return;
        }

        MutationPoolManager manager = MutationPoolManager.get(serverLevel);
        long gameTick = serverLevel.getGameTime();
        long interval = currentInterval();
        long periodIndex = MutationHelper.periodIndex(gameTick, interval);
        long worldSeed = serverLevel.getSeed();

        List<Block> pool = manager.getEffectivePool(pos, state);
        double chance = MutationHelper.mutationChance(MutationHelper.currentStage(gameTick));
        BlockState target = MutationHelper.getTarget(state, pos, worldSeed, periodIndex, pool, chance);

        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        breakData.start(target, periodIndex);
    }

    /** 方块破坏：执行真实转换。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.isCreative()) {
            return;
        }
        BlockPos pos = event.getPos();
        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        if (!breakData.isActive()) {
            return;
        }

        BlockState targetState = breakData.getTargetState();
        breakData.clear();

        if (targetState == null) {
            return;
        }

        // 取消默认掉落与经验，自行处理转换
        event.setCanceled(true);

        // 清除原方块
        serverLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                targetState, serverLevel, pos, null, player, ItemStack.EMPTY);

        // 生存模式：生成目标方块的掉落物实体与经验
        for (ItemStack drop : drops) {
            net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                    serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
            item.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(item);
        }
        int exp = targetState.getExpDrop(serverLevel, pos, null, player, ItemStack.EMPTY);
        if (exp > 0) {
            targetState.getBlock().popExperience(serverLevel, pos, exp);
        }
    }

    /** 根据当前末日阶段获取转换周期（阶段系统后续接入，暂用基础值）。 */
    private static long currentInterval() {
        return FocalDecayConfig.BASE_INTERVAL.get();
    }
}
