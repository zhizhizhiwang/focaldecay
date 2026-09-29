package com.zhizhiwang.focal_decay.mutation;

/**
 * 催化域：把某一片区域的"是否发生失焦"从<b>概率</b>变成<b>必然</b>
 * （2026-09-29，{@code DESIGN.md} §13.8）。
 * <p>
 * <b>它补的是哪个洞</b>：引导模型只决定"变成什么"（落点），不决定"变不变"。
 * 而阶段 1 的每周期命中率只有 0.01——在一个 5 秒一个周期的世界里，玩家再怎么引导也看不见效果。
 * 他缺的动词不是"我要它变成什么"，而是"我要它<b>现在</b>就变"。
 * <p>
 * 两级形态共用这一个输入（{@code DESIGN.md} §13.8）：
 * <ul>
 *   <li><b>催化域</b>（工具）：区域内 {@link #forced()}，立即生效、持续若干周期；</li>
 *   <li><b>沉降仪式</b>（产能）：同样依赖"必中"，但落点由投入的模型概念决定，规模大得多。</li>
 * </ul>
 * <p>
 * <b>R2「形可变、量不减」的落点</b>：{@link #wildBonus()} 是<b>域外一圈</b>的大池概率增量——
 * 你能决定中心变成什么，但每用一次，周围就多一片你控制不了的变化。
 * 于是"基地"永远不可能同时是工厂与堡垒。
 * <p>
 * <b>为什么 spill 圈不给"跨 tier"例外</b>：越级（{@code guide_up_tier_chance}）只在
 * {@link #forced()} 的区域内生效。spill 圈是<b>无法瞄准</b>的漂移，
 * 让它也能越级等于给世界开了一个随机产矿的口子——那正是 tier 护栏要堵的东西。
 * <p>
 * <b>两端一致性</b>（§3.2）：本对象由"区域效果"解析而来（服务端 {@code MutationPoolManager}、
 * 客户端 {@code ClientRegionData} 各自的镜像），走的是与保护范围/引导模型同一条同步通道，
 * 所以它不需要额外的快照字段——但它<b>必须</b>是解析的显式输入，
 * 否则"算得一样"就只靠两边代码恰好写得一致。
 *
 * @param forced    本区域每周期命中概率被抬到 {@value #FORCED_CHANCE}
 * @param wildBonus 域外一圈的 {@code wild_chance} 增量（0 = 没有 spill）
 */
public record Catalysis(boolean forced, double wildBonus) {

    /** 催化区的每周期命中概率：必中。 */
    public static final double FORCED_CHANCE = 1.0;

    /** 什么都不催化。 */
    public static final Catalysis NONE = new Catalysis(false, 0.0);

    /** 规范化：概率是 (0,1) 里的量，越界的输入一律夹住（两端都必须得到同一个值）。 */
    public Catalysis {
        wildBonus = Math.max(0.0, Math.min(1.0, wildBonus));
    }

    /** 有没有任何效果（省掉热路径上的一次取值）。 */
    public boolean isActive() {
        return forced || wildBonus > 0.0;
    }
}
