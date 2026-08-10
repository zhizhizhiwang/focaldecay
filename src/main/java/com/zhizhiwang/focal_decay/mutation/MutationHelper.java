package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 确定性随机与目标计算（设计大纲 §5）。
 * 服务端与客户端使用相同种子，保证两侧结果一致。
 */
public final class MutationHelper {

    private MutationHelper() {
    }

    /**
     * 计算某方块在本周期的突变目标。
     * seed = mix64(pos.asLong() ^ worldSeed ^ periodIndex)
     * 池为空时返回原方块；概率 roll 使用同一确定性种子，两端结果一致。
     * <p>
     * 注意：原始异或值必须经过 mix64 雪崩混合再交给 LCG——
     * periodIndex 是小数字，直接异或只扰动低几位，LCG 首次采样几乎不变，
     * 会导致"同一批固定位置每周期都失焦"。
     */
    public static BlockState getTarget(BlockState original, BlockPos pos, long worldSeed, long periodIndex, List<Block> pool, double probability) {
        if (pool.isEmpty() || probability <= 0.0) {
            return original;
        }
        long seed = mix64(pos.asLong() ^ worldSeed ^ periodIndex);
        RandomSource random = RandomSource.create(seed);
        if (probability < 1.0 && random.nextDouble() >= probability) {
            return original;
        }
        return pool.get(random.nextInt(pool.size())).defaultBlockState();
    }

    /** SplitMix64 雪崩混合：微小输入变化（如周期 +1）也能让输出完全发散。 */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * 阶段判定（临时实现）：按原版天数 gameTick / 24000。
     * 待 §6 末日阶段系统接入自定义天数 SavedData 后替换。
     */
    public static int currentStage(long gameTick) {
        long day = gameTick / 24000L;
        if (day >= FocalDecayConfig.STAGE3_DAY.get()) {
            return 3;
        }
        if (day >= FocalDecayConfig.STAGE2_DAY.get()) {
            return 2;
        }
        return 1;
    }

    /** 当前阶段的方块转换概率（Server 配置，同步到客户端）。 */
    public static double mutationChance(int stage) {
        return switch (stage) {
            case 2 -> FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE2.get();
            case 3 -> FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE3.get();
            default -> FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE1.get();
        };
    }

    /** 计算种子（供外部复用的确定性随机源）。 */
    public static long seed(BlockPos pos, long worldSeed, long periodIndex) {
        return pos.asLong() ^ worldSeed ^ periodIndex;
    }

    /** 当前周期索引：gameTick / conversionInterval。 */
    public static long periodIndex(long gameTick, long conversionInterval) {
        return gameTick / Math.max(1L, conversionInterval);
    }
}
