package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 末影龙遗物宝箱（2026-08-21）：击败末影龙后，在末地传送门平台上生成一只宝箱，
 * 内含未激活完全稳定模型 + 碎片·Aaron的誓约——用箱子替代掉落物，防止掉进虚空/火焰。
 * 位置：主岛地面、传送门正下方附近（中心偏移 10~14 格，种子决定方向），
 * 避免放在传送门平台上被龙死亡时的传送门生成顶掉。
 * 掉落概率由 {@code ender_dragon_total_stability_drop_chance} 控制（默认 1.0 必出）。
 */
public final class DragonChestHandler {

    private DragonChestHandler() {
    }

    @SubscribeEvent
    public static void onDragonDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || !(dragon.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.random.nextDouble() >= FocalDecayConfig.ENDER_DRAGON_TOTAL_STABILITY_DROP_CHANCE.get()) {
            return;
        }
        BlockPos pos = rewardPos(serverLevel);
        serverLevel.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (serverLevel.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(ModItems.TOTAL_STABILITY_MODEL.get()));
            chest.setItem(1, new ItemStack(ModItems.FRAGMENT_AARON.get()));
        }
        FocalDecay.LOGGER.info("Focal Decay: Dragon relic chest placed at {}", pos);
    }

    /** 奖励位置：主岛地面、中心偏移 10~14 格（种子决定方向），避开传送门/龙蛋/黑曜石柱。 */
    private static BlockPos rewardPos(ServerLevel level) {
        RandomSource random = RandomSource.create(level.getSeed() ^ 0x1A2B3C4DL);
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = 10.0 + random.nextDouble() * 4.0;
        int x = (int) Math.round(Math.cos(angle) * distance);
        int z = (int) Math.round(Math.sin(angle) * distance);
        level.getChunk(x >> 4, z >> 4); // 确保主岛区块生成
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new BlockPos(x, y + 1, z);
    }
}
