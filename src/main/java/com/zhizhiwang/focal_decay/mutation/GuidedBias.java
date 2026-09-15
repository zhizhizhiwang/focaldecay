package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;

/**
 * 引导偏向（方案 A，2026-08-21）：概念内成员抽中突变时，
 * 以概率 q 从概念邻域选目标、以 1−q 回退到常规分支（局部并集 / 大池）。
 * 两端（服务端/客户端）共用同一记录，保证确定性一致。
 * <p>
 * 2026-09-15 起概念邻域是预计算的 {@link ClassifiedPool}（按形态类切好、按注册表 id 定序），
 * 而不是每次现算现排的 {@code List<Block>}；同时"是否生效"要连<b>形态类</b>一起看：
 * 一个只训练了原木与木板的引导模型，不该把橡木楼梯变成木板（几何会被破坏）。
 */
public record GuidedBias(ClassifiedPool pool, double q) {
    /** 无引导：完全走常规分支。 */
    public static final GuidedBias NONE = new GuidedBias(null, 0.0);

    /** 该偏向对某个形态类是否实际生效（q > 0 且概念池在该形态类下有候选）。 */
    public boolean active(int shapeClass) {
        return q > 0.0 && pool != null && pool.count(shapeClass) > 0;
    }
}
