package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * 锚固化的耗时/规模埋点（BACKLOG P0-1）。
 * <p>
 * <b>为什么需要它</b>：{@link MutationEventHandler#convertPrototypeRange} 在半径 32（完全稳定模型 /
 * 已完成的候选观测者）时要遍历 {@code 65³ ≈ 274,625} 个坐标，逐个读方块、按累积回扫（上限 128 周期）
 * 解析、再 {@code setBlock(p, target, 3)}。这个成本一直只有"一次性卡顿"这一定性描述，
 * 没有数字，因此无法判断该优化到什么程度才算够。先量，再改。
 * <p>
 * 本类只做测量，不改变任何行为。几个取舍：
 * <ul>
 *   <li>用 {@link System#nanoTime()}：单调、不受系统时间调整影响；</li>
 *   <li>输出走 INFO 且**每次调用只打一行**：锚固化是玩家手动触发的稀有事件（放置基座 / 插入模型），
 *       不会刷屏，因此不需要采样或限流；</li>
 *   <li>额外统计**当前线程的 CPU 时间**：耗时≈CPU 说明是纯计算；耗时远大于 CPU 说明时间花在阻塞上
 *       （区块加载、IO）。这条区分决定了优化方向，所以值得多打一个数。</li>
 * </ul>
 * 日志一律 ASCII（见 AGENTS.md §3.1：本项目控制台是 GBK，中文会变乱码）。
 */
public final class AnchorNormalizeProfiler {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static long totalCalls;
    private static long totalNanos;
    private static long totalScanned;
    private static long totalChanged;
    /** 单次调用里最慢的一次，用来判断最坏情况是否值得担心。 */
    private static long worstNanos;
    private static int worstRadius = -1;

    private AnchorNormalizeProfiler() {
    }

    /** 记录一次完整的锚固化调用。 */
    public static void record(ServerLevel level, BlockPos center, int radius,
                              long scanned, long changed, long elapsedNanos, long cpuNanos) {
        totalCalls++;
        totalNanos += elapsedNanos;
        totalScanned += scanned;
        totalChanged += changed;
        if (elapsedNanos > worstNanos) {
            worstNanos = elapsedNanos;
            worstRadius = radius;
        }

        long positions = (long) (2 * radius + 1) * (2 * radius + 1) * (2 * radius + 1);
        FocalDecay.LOGGER.info(
                "[focal_decay] anchor normalize: dim={} center={} radius={} positions={}"
                        + " scanned={} changed={} elapsed={} ms cpu={} ms throughput={} kPos/s",
                level.dimension().location(),
                center.toShortString(),
                radius,
                positions,
                scanned,
                changed,
                ms(elapsedNanos),
                ms(cpuNanos),
                elapsedNanos <= 0 ? -1L : scanned * 1_000_000L / elapsedNanos);
    }

    /**
     * 调用点要在固化**之前**取一次这个值，结束后与新的读数相减得到本次的 CPU 时间。
     * 返回 {@code -1} 表示平台不支持，日志里表现为 {@code cpu=-1 ms}，属可接受的降级。
     * <p>
     * 注意 {@code ThreadMXBean#getCurrentThreadCpuTime()} 返回的是线程**累计** CPU 时间，
     * 不是本次调用的耗时——所以必须取差值，不能直接打读数。
     */
    public static long cpuClockNanos() {
        try {
            return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 累计统计，供命令查询（已格式化好，保持调用方简单）。 */
    public static String summary() {
        if (totalCalls == 0) {
            return "anchor normalize: no calls recorded yet";
        }
        return "anchor normalize: calls=" + totalCalls
                + " total=" + ms(totalNanos) + " ms"
                + " avg=" + (totalNanos / totalCalls / 1_000_000L) + " ms"
                + " worst=" + ms(worstNanos) + " ms (radius " + worstRadius + ")"
                + " scanned=" + totalScanned
                + " changed=" + totalChanged;
    }

    private static long ms(long nanos) {
        return nanos < 0 ? -1L : nanos / 1_000_000L;
    }
}
