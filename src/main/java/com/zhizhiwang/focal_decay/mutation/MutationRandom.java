package com.zhizhiwang.focal_decay.mutation;

import net.minecraft.core.BlockPos;

/**
 * 无分配的确定性随机源（2026-09-15 重写，替换 {@code RandomSource.create}）。
 * <p>
 * 旧实现在累积回退扫描里每一步都 {@code RandomSource.create(seed)}，也就是
 * {@code new LegacyRandomSource} + 一个 {@code AtomicLong}；阶段 1 概率 0.01 时
 * 期望回扫 100 步，等于每个可见方块每周期分配约 100 个对象。这里把随机数做成
 * <b>纯函数</b>：状态就是一个 {@code long}，调用方自己拿着走，
 * 全程零分配、零虚调用、零同步。
 * <p>
 * 另一个同样重要的理由是<b>契约稳定性</b>：{@code RandomSource.create} 返回什么实现
 * 由原版决定（历史上从 LCG 迁到过别的实现），一旦原版换实现，所有老存档的失焦目标
 * 会整体重排。自己实现的算法只由本文件的常数决定，跨版本、跨 JVM 完全稳定。
 * <p>
 * 浮点也只走 IEEE754 精确路径（{@code (long >>> 11) * 2^-53}），不做任何超越函数调用，
 * 因此服务端与客户端结果逐位一致。
 */
public final class MutationRandom {

    /** SplitMix64 的黄金比例增量。 */
    private static final long GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    private MutationRandom() {
    }

    /** SplitMix64 雪崩混合：微小输入变化（如周期 +1）也能让输出完全发散。 */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * MIX_A;
        z = (z ^ (z >>> 27)) * MIX_B;
        return z ^ (z >>> 31);
    }

    /**
     * 确定性种子（设计大纲 §5.1 修订，2026-08-20）：
     * 三个坐标分量分别乘不同的大常数后异或，再经 SplitMix64 雪崩混合。
     * <p>
     * 旧公式 {@code pos.asLong() ^ worldSeed ^ period} 中 y 只占最低 12 位，与 period/worldSeed
     * 的低位异或纠缠，再经 LegacyRandomSource 48 位截断 + 低概率累积回退扫描后，
     * 纵向（y 变化）的熵会被吃掉——实测阶段1（概率 0.1）一列 32 格只有 3 个不同目标、
     * 相邻 21 格相同。改为坐标独立哈希后，纵向与平面随机性一致（实测 27~28/32 不同）。
     * <p>
     * 公式与历史实现逐字一致：改这里等于让所有存档的失焦目标整体重排。
     */
    public static long seed(BlockPos pos, long worldSeed, long period) {
        long h = (long) pos.getX() * 0x9E3779B97F4A7C15L
                ^ (long) pos.getY() * 0xC2B2AE3D27D4EB4FL
                ^ (long) pos.getZ() * 0x165667B19E3779F9L
                ^ worldSeed
                ^ period;
        return mix64(h);
    }

    /** 前进一步：{@code state -> next state}，同样是纯函数。 */
    public static long next(long state) {
        return mix64(state + GAMMA);
    }

    /** {@code [0,1)} 双精度：取高 53 位，完全由整数位决定。 */
    public static double toDouble(long state) {
        return (state >>> 11) * 0x1.0p-53;
    }

    /**
     * 无偏 {@code [0,bound)}。
     * <p>
     * 用 Lemire 的乘移位法（{@code Math.unsignedMultiplyHigh}，Java 18+）：把 {@code state}
     * 当作 64 位无符号数整体乘以 bound 再取高 64 位，等价于 {@code floor(state * bound / 2^64)}
     * 的均匀映射，偏差量级 2^-64 —— 对突变池而言完全可忽略，而且没有 {@code Random#nextInt}
     * 那种拒绝采样循环（循环会让"走几步"变得不可预测，不利于确定性推理）。
     * <p>
     * 注意必须用完整的 64 位状态：先右移砍掉符号位会让结果只落在 {@code [0, bound/2)}。
     */
    public static int nextInt(long state, int bound) {
        if (bound <= 1) {
            return 0;
        }
        return (int) Math.unsignedMultiplyHigh(state, bound);
    }

    /** 从 {@code [0,1)} 的判定：把"掷一次骰子"和"状态前进一步"合并成一个调用，避免调用点写错顺序。 */
    public static boolean chance(long state, double probability) {
        return probability > 0.0 && toDouble(state) < probability;
    }
}
