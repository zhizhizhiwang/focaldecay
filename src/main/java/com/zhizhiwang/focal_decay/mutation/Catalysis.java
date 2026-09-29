package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.Tiers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

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

    /**
     * 一片催化域的描述（{@code DESIGN.md} §13.8 的"形式一"）。
     * <p>
     * <b>为什么不落盘</b>：一片域只活 {@code until - 现在} 个周期（默认几十个周期 = 几分钟），
     * 服务器重启把它丢掉是合理的——它不是世界状态，是一次<b>正在发生的事件</b>。
     * 落盘反而要处理"重启后倒计时怎么算"这种没人关心的问题。
     * <p>
     * <b>几何用切比雪夫距离</b>（与原型机的 {@code withinRadius} 同一套）：
     * 域是一个正方体，外面再套一圈 {@code ringWidth} 厚的壳作为 spill 圈。
     *
     * @param center    域中心（催化剂方块的位置）
     * @param radius    必中区的半边长（格）
     * @param ringWidth spill 圈的厚度（格）
     * @param until     生效到哪个显示刻为止（{@code < 0} = 没有域）
     * @param spill     spill 圈的 {@code wild_chance} 增量
     * @param concept   点火时插着的模型指认的概念标签（{@code ""} = 没有概念，这时火只负责"必中"）
     * @param q         那个模型的完备度
     */
    public record Field(BlockPos center, int radius, int ringWidth, long until, double spill,
                        String concept, double q) {

        /** 到中心的切比雪夫距离。 */
        public static int distance(BlockPos a, BlockPos b) {
            return Math.max(Math.abs(a.getX() - b.getX()),
                    Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
        }

        /** 有效期判据：两端各拿自己的显示刻算，所以"什么时候结束"本身不需要同步。 */
        public boolean isActive(long period) {
            return until >= 0 && period <= until;
        }

        /**
         * 这片域给某个源方块的<b>引导偏向</b>（2026-09-29，作者实机反馈后改）。
         * <p>
         * <b>这里与原型机的引导有一处关键差别：跳过"源方块必须属于概念"这道门。</b>
         * 原型机的引导是背景性的（观测雪梨不会让苹果更容易失焦——出处见 {@code DESIGN.md} §4.2），
         * 而点火是观测者<b>点名</b>：他说这片区域归"矿物"管，石头就按矿物来解释。
         * 没有这一条，作者那次"石头底座 + 矿物模型"的实验永远不可能有反应
         * （石头不是矿物概念的成员）。
         * <p>
         * <b>另一处差别：优先取"恰好高一档"的概念成员。</b>
         * 只把档位上限抬一级是不够的——池子里成员那么多，抬了上限也大概率抽到同档的东西，
         * 玩家看到的仍然"没什么变化"（这正是作者反馈 #6）。所以这里直接把池子换成
         * "概念 ∩ 目标档位"；那一档在概念里不存在时，退回完整的概念池（而不是什么都没有）。
         * <p>
         * q 依然决定"有多大概率按这个概念来"（其余回退常规池），所以完备度仍然是有意义的进度：
         * q = 1.0 才是"说了就一定算"。
         */
        public GuidedBias biasFor(MutationIndex index, BlockState source) {
            if (concept.isEmpty() || q <= 0.0 || index == null) {
                return GuidedBias.NONE;
            }
            int targetTier = Math.min(index.tier(source.getBlock()) + 1, Tiers.MAX_NATURAL_TIER);
            ClassifiedPool tiered = index.taggedAtTier(concept, targetTier);
            return new GuidedBias(tiered.isEmpty() ? index.tagged(concept) : tiered, q);
        }

        /** 这个位置受到的催化：域内必中、域外一圈只有 spill、更远什么都没有。 */
        public Catalysis at(BlockPos pos, long period) {
            if (!isActive(period)) {
                return NONE;
            }
            int distance = distance(center, pos);
            if (distance <= radius) {
                return new Catalysis(true, 0.0);
            }
            if (distance <= radius + ringWidth) {
                return new Catalysis(false, spill);
            }
            return NONE;
        }
    }

    /**
     * 多片域重叠时的合并规则（<b>纯函数，且与列表顺序无关</b>）：命中任何一片的必中区就是必中，
     * 否则取最大的 spill。
     * <p>
     * 顺序无关是硬要求：服务端按登记顺序遍历、客户端按"整表 + 增量到达顺序"遍历，
     * 两种顺序不保证一致（与 {@code GuidedConcept#betterGuided} 同一条理由）。
     */
    /**
     * 多片域里取"概念偏向"最强的一片（{@code q} 大者胜，并列按中心坐标字典序——
     * 与 {@code GuidedConcept#betterGuided} 同一条理由：两端的列表顺序不保证一致）。
     * 没有任何一片带概念时返回 {@link GuidedBias#NONE}。
     */
    public static GuidedBias biasAt(List<Field> fields, BlockPos pos, long period,
                                     MutationIndex index, BlockState source) {
        Field best = null;
        for (Field field : fields) {
            if (field.concept().isEmpty() || field.q() <= 0.0 || !field.at(pos, period).isActive()) {
                continue;
            }
            if (best == null || field.q() > best.q()
                    || (field.q() == best.q() && compareCenters(field.center(), best.center()) < 0)) {
                best = field;
            }
        }
        return best == null ? GuidedBias.NONE : best.biasFor(index, source);
    }

    private static int compareCenters(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    public static Catalysis at(List<Field> fields, BlockPos pos, long period) {
        boolean anyForced = false;
        double bestSpill = 0.0;
        for (Field field : fields) {
            Catalysis one = field.at(pos, period);
            anyForced |= one.forced();
            if (one.wildBonus() > bestSpill) {
                bestSpill = one.wildBonus();
            }
        }
        return anyForced || bestSpill > 0.0 ? new Catalysis(anyForced, bestSpill) : NONE;
    }
}
