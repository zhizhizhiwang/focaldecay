package com.zhizhiwang.focal_decay.mutation;

import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 引导偏向（方案 A，2026-08-21）：概念内成员抽中突变时，
 * 以概率 q 从概念邻域选目标、以 1−q 回退全局池。
 * 两端（服务端/客户端）共用同一记录，保证确定性一致。
 */
public record GuidedBias(List<Block> conceptPool, double q) {
    /** 无引导：完全走全局池。 */
    public static final GuidedBias NONE = new GuidedBias(List.of(), 0.0);

    /** 该偏向是否实际生效（q > 0 且概念邻域非空）。 */
    public boolean active() {
        return q > 0.0 && !conceptPool.isEmpty();
    }
}
