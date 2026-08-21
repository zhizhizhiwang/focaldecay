package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.attachment.BreakData;
import com.zhizhiwang.focal_decay.attachment.ModAttachments;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
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
        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        if (FocalDecayWorldData.get(serverLevel.getServer()).isObserverOnline()) {
            breakData.clear(); // 失焦终止：清掉可能的陈旧锁定
            return; // 失焦终止：不再锁定突变目标
        }

        MutationPoolManager manager = MutationPoolManager.get(serverLevel);
        long days = FocalDecayWorldData.get(serverLevel.getServer()).getDays();
        int stage = MutationHelper.currentStage(days);

        // 带方块实体的方块、空气、黑名单、非本阶段转换源：不参与转换
        if (!MutationHelper.isConversionSource(state, serverLevel, pos, stage)) {
            breakData.clear(); // 非转换源（门/楼梯/栅栏等）：清掉陈旧锁定，避免掉落泄漏
            return;
        }

        long gameTick = serverLevel.getGameTime();
        long periodIndex = MutationHelper.blockPeriod(gameTick);
        long worldSeed = serverLevel.getSeed();

        List<Block> pool = manager.getGlobalPool().snapshot();
        double chance = MutationHelper.mutationChance(stage);
        MutationHelper.Protection protection = manager.protectionInfo(pos, state, stage);
        long birthPeriod = manager.getBlockBirthPeriod(pos);
        GuidedBias bias = manager.getGuidedBias(pos, state, stage);
        BlockState target = MutationHelper.getVisibleTarget(state, pos, worldSeed, periodIndex, pool, chance,
                bias, protection, birthPeriod);

        breakData.start(target, periodIndex, pos);
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
        BlockState sourceState = event.getState();
        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        if (!breakData.isActive()) {
            return;
        }
        if (!pos.equals(breakData.getPos())) {
            breakData.clear(); // 位置不匹配：陈旧的锁定（上次挖掘的目标残留）
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

        // 工具传入玩家主手物品：目标方块的"挖掘等级"（requiresCorrectToolForDrops）
        // 由当前可见目标决定——拿对工具才有对应掉落，拿错则无掉落（与原版一致）
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                targetState, serverLevel, pos, null, player, player.getMainHandItem());

        // 生存模式：生成目标方块的掉落物实体与经验
        for (ItemStack drop : drops) {
            net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                    serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
            item.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(item);
        }
        int exp = targetState.getExpDrop(serverLevel, pos, null, player, player.getMainHandItem());
        if (exp > 0) {
            targetState.getBlock().popExperience(serverLevel, pos, exp);
        }

        // 原版 destroyBlock 在 BreakEvent 取消后跳过了 mineBlock 的耐久消耗，这里手动补上：
        // 按"当前可见目标"结算（目标可破坏速度非 0 时扣 2 耐久，与原版一致）
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            held.mineBlock(serverLevel, targetState, pos, player);
        }

        // 铜块失焦突变：概率掉落"硫铜结晶"语义碎片（设计大纲 §11 来源 5）
        if (targetState.getBlock() != sourceState.getBlock()
                && sourceState.is(Blocks.COPPER_BLOCK)
                && serverLevel.random.nextDouble() < FocalDecayConfig.FRAGMENT_COPPER_MUTATION_CHANCE.get()) {
            net.minecraft.world.entity.item.ItemEntity fragment = new net.minecraft.world.entity.item.ItemEntity(
                    serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new ItemStack(ModItems.FRAGMENT_CRYSTAL.get()));
            fragment.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(fragment);
        }
    }

}
