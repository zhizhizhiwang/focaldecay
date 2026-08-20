package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 确定性随机与目标计算（设计大纲 §5）。
 * 服务端与客户端使用相同种子，保证两侧结果一致。
 */
public final class MutationHelper {
    /** 累积转换回退扫描的上限（周期数）。超出视为"从未抽中"，概率上已可忽略。 */
    private static final int CUMULATIVE_SCAN_CAP = 128;

    private MutationHelper() {
    }

    /**
     * 计算某方块在"单个周期"的突变目标（无记忆，抽不中就回原方块）。
     * 有记忆的累积转换请走 {@link #getVisibleTarget}。
     * 池为空时返回原方块；概率 roll 使用同一确定性种子，两端结果一致。
     */
    public static BlockState getTarget(BlockState original, BlockPos pos, long worldSeed, long periodIndex, List<Block> pool, double probability) {
        if (pool.isEmpty() || probability <= 0.0) {
            return original;
        }
        long seed = seedFor(pos, worldSeed, periodIndex);
        RandomSource random = RandomSource.create(seed);
        if (probability < 1.0 && random.nextDouble() >= probability) {
            return original;
        }
        return pool.get(random.nextInt(pool.size())).defaultBlockState();
    }

    /**
     * 统一的方块识别函数：生存破坏、创造中键选取、客户端预览共用。
     * 受稳定锚保护的方块一律返回原方块（不转换、不显示幽灵）。
     */
    public static BlockState getVisibleTarget(BlockState original, BlockPos pos, long worldSeed, long periodIndex,
                                              List<Block> pool, double probability, boolean isProtected,
                                              long birthPeriod) {
        if (isProtected) {
            return original;
        }
        // 玩家放置的方块：从"放置周期 + 1"才开始崩坏，放置瞬间保持原方块。
        long fromPeriod = birthPeriod >= 0 ? birthPeriod + 1 : 0;
        if (periodIndex < fromPeriod) {
            return original;
        }
        return cumulativeTarget(original, pos, worldSeed, periodIndex, pool, probability, fromPeriod);
    }

    /**
     * 有记忆的累积转换（阶段1/2 与阶段3 的方块部分）：
     * 从当前周期向前回退，找到最近一次"抽中突变"的周期，返回该周期的目标；
     * 抽中前的周期之间状态保持不变——未抽中的方块保留上一次材质，而不是回退原方块。
     * 命中条件即阶段概率（0.1/0.6/1.0）：每周期以该概率掷骰，抽中换新材质，
     * 未抽中保留上次材质，实现世界随周期逐渐积累崩坏。
     * 注意：阶段切换（含 /focaldecay days 指令）会改变命中概率，使"最近抽中周期"
     * 整体前移/后移，已失焦方块的目标材质随之重排——这是预期的确定性行为。
     * 服务端与客户端共用同一公式，保证预览与真实转换一致。
     */
    private static BlockState cumulativeTarget(BlockState original, BlockPos pos, long worldSeed, long periodIndex,
                                               List<Block> pool, double probability, long fromPeriod) {
        if (pool.isEmpty() || probability <= 0.0) {
            return original;
        }
        long span = periodIndex - fromPeriod + 1;
        int cap = (int) Math.min(span, CUMULATIVE_SCAN_CAP);
        for (int back = 0; back < cap; back++) {
            long period = periodIndex - back;
            long seed = seedFor(pos, worldSeed, period);
            RandomSource random = RandomSource.create(seed);
            if (random.nextDouble() >= probability) {
                continue;
            }
            return pool.get(random.nextInt(pool.size())).defaultBlockState();
        }
        return original;
    }

    /** SplitMix64 雪崩混合：微小输入变化（如周期 +1）也能让输出完全发散。 */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * 确定性种子（设计大纲 §5.1 修订，2026-08-20）：
     * 三个坐标分量分别乘不同的大常数后异或，再经 SplitMix64 雪崩混合。
     * <p>
     * 旧公式 pos.asLong() ^ worldSeed ^ period 中 y 只占最低 12 位，与 period/worldSeed
     * 的低位异或纠缠，再经 LegacyRandomSource 48 位截断 + 低概率累积回退扫描后，
     * 纵向（y 变化）的熵会被吃掉——实测阶段1（概率 0.1）一列 32 格只有 3 个不同目标、
     * 相邻 21 格相同。改为坐标独立哈希后，纵向与平面随机性一致（实测 27~28/32 不同）。
     */
    private static long seedFor(BlockPos pos, long worldSeed, long period) {
        long h = (long) pos.getX() * 0x9E3779B97F4A7C15L
                ^ (long) pos.getY() * 0xC2B2AE3D27D4EB4FL
                ^ (long) pos.getZ() * 0x165667B19E3779F9L
                ^ worldSeed
                ^ period;
        return mix64(h);
    }

    /**
     * 阶段判定：按末日天数（FocalDecayWorldData，20 分钟游戏日 +1，玩家为 0 暂停）。
     * 阶段可配置关闭（enable_stage_system=false 时恒为阶段 1）。
     */
    public static int currentStage(long days) {
        if (!FocalDecayConfig.ENABLE_STAGE_SYSTEM.get()) {
            return 1;
        }
        if (days >= FocalDecayConfig.STAGE3_DAY.get()) {
            return 3;
        }
        if (days >= FocalDecayConfig.STAGE2_DAY.get()) {
            return 2;
        }
        return 1;
    }

    /** 各阶段的转换周期（tick）。 */
    public static long intervalForStage(int stage) {
        return switch (stage) {
            case 2 -> FocalDecayConfig.STAGE2_INTERVAL.get();
            case 3 -> FocalDecayConfig.STAGE3_INTERVAL.get();
            default -> FocalDecayConfig.BASE_INTERVAL.get();
        };
    }

    /** 当前阶段的方块转换概率（Server 配置，同步到客户端）。 */
    public static double mutationChance(int stage) {
        return switch (stage) {
            case 2 -> FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE2.get();
            case 3 -> FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE3.get();
            default -> FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE1.get();
        };
    }

    /**
     * 方块是否可作为当前阶段的"转换源"（设计大纲 §6.3）：
     *  - 阶段1：仅完整方块（isCollisionShapeFullBlock）；
     *  - 阶段2+：额外包含非完整但有碰撞箱的方块（栅栏、玻璃板、台阶等）；
     *  - 空气、带方块实体、黑名单方块始终排除。
     */
    public static boolean isConversionSource(BlockState state, BlockGetter level, BlockPos pos, int stage) {
        if (state.isAir() || state.hasBlockEntity() || state.is(ModTags.Blocks.CONVERSION_BLACKLIST)) {
            return false;
        }
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return true;
        }
        return stage >= 2 && !state.getCollisionShape(level, pos).isEmpty();
    }

    /** 计算种子（供外部复用的确定性随机源）。 */
    public static long seed(BlockPos pos, long worldSeed, long periodIndex) {
        return seedFor(pos, worldSeed, periodIndex);
    }

    /** 当前周期索引：gameTick / conversionInterval。 */
    public static long periodIndex(long gameTick, long conversionInterval) {
        return gameTick / Math.max(1L, conversionInterval);
    }

    /**
     * 方块突变的周期基准：固定为 base_interval，与阶段无关。
     * 旧实现用各阶段 interval（100/60/40）计算周期，阶段切换时周期编号跳变，
     * 导致全图方块目标瞬间重排；固定基准后阶段切换只改变概率门控与影响范围。
     */
    public static long blockPeriod(long gameTick) {
        return gameTick / Math.max(1L, FocalDecayConfig.BASE_INTERVAL.get());
    }
}
