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
 *   <li><b>催化域</b>（工具）：点火那一刻按概念写进世界，此后域内持续必中；</li>
 *   <li><b>沉降仪式</b>（产能）：同样依赖"必中"，但落点由投入的模型概念决定，规模大得多。</li>
 * </ul>
 * <p>
 * <b>R2「形可变、量不减」的落点</b>：域外一圈的 spill 圈是<b>代价</b>——
 * 那里既更容易失焦（{@link #chanceBonus()}），落点也更失控（{@link #wildBonus()}）。
 * 你能决定中心变成什么，但每用一次，周围就多一片你控制不了的变化。
 * 于是"基地"永远不可能同时是工厂与堡垒。
 * <p>
 * <b>为什么 spill 圈不给"跨 tier"例外</b>：越级（{@link #climb()}）只在点火那一次生效
 * （作者裁定，2026-09-29）。见 {@link #climb()}。
 * <p>
 * <b>两端一致性</b>（§3.2）：本对象由"区域效果"解析而来（服务端 {@code MutationPoolManager}、
 * 客户端 {@code ClientRegionData} 各自的镜像），走的是与保护范围/引导模型同一条同步通道，
 * 所以它不需要额外的快照字段——但它<b>必须</b>是解析的显式输入，
 * 否则"算得一样"就只靠两边代码恰好写得一致。
 *
 * @param forced      本区域每周期命中概率被抬到 {@value #FORCED_CHANCE}（域内）
 * @param chanceBonus 命中率增量（域外 spill 圈；0 = 没有 spill）
 * @param wildBonus   {@code wild_chance} 增量，即"命中之后走大池"的比例（域外 spill 圈）
 * @param climb       允许<b>越级</b>（目标比源高一档）：只有点火那一次为 true
 */
public record Catalysis(boolean forced, double chanceBonus, double wildBonus, boolean climb) {

    /** 催化区的每周期命中概率：必中。 */
    public static final double FORCED_CHANCE = 1.0;

    /** 什么都不催化。 */
    public static final Catalysis NONE = new Catalysis(false, 0.0, 0.0, false);

    /**
     * A/B 开关（{@code -Dfocaldecay.abNoSpillChance=true}）：把 spill 圈的命中率增量清零。
     * <p>
     * 用来证明"域外真的更容易失焦"那条断言<b>真的在测东西</b>——关掉之后它必须 FAIL。
     * 与 {@code Tiers.setGateDisabledForTest} 同一套做法（{@code AGENTS.md} §4 的 A/B 硬要求）。
     */
    private static final boolean AB_NO_SPILL_CHANCE =
            Boolean.getBoolean("focaldecay.abNoSpillChance");

    /**
     * A/B 开关（{@code -Dfocaldecay.abNoFieldBias=true}）：掐掉域内的概念落点
     * （由 {@code MutationPoolManager#catalystBiasAt} 使用）。
     * <p>
     * 用来证明"域内的必中必须配概念落点"那条断言真的在测东西——掐掉之后域内会按局部/大池抽，
     * 点火刚写出来的矿会被下一次重抽吃掉。
     * <p>
     * 读一次就够了（{@code static final}）：它会被解析热路径逐方块问到，
     * 每次去查系统属性等于把一次字符串查找塞进内循环。
     */
    private static final boolean AB_NO_FIELD_BIAS =
            Boolean.getBoolean("focaldecay.abNoFieldBias");

    /** 域内的概念落点是否被 A/B 开关掐掉了（见 {@link #AB_NO_FIELD_BIAS}）。 */
    public static boolean fieldBiasDisabledForTest() {
        return AB_NO_FIELD_BIAS;
    }

    /** 规范化：概率都是 (0,1) 里的量，越界的输入一律夹住（两端都必须得到同一个值）。 */
    public Catalysis {
        chanceBonus = Math.max(0.0, Math.min(1.0, chanceBonus));
        wildBonus = Math.max(0.0, Math.min(1.0, wildBonus));
    }

    /** 有没有"是否发生"或"落点"上的效果（省掉热路径上的一次取值）。 */
    public boolean isActive() {
        return forced || chanceBonus > 0.0 || wildBonus > 0.0;
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
     * @param spill     spill 圈的代价强度，同时进命中率与 {@code wild_chance}（0 = 没有代价）
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
         * 这片域给某个源方块的<b>引导偏向</b>：域内的概念，且<b>跳过"源方块必须属于概念"这道门</b>。
         * <p>
         * <b>为什么要跳门</b>：原型机的引导是背景性的（观测雪梨不会让苹果更容易失焦——出处见
         * {@code DESIGN.md} §4.2），而点火是观测者<b>点名</b>：他说这片区域归"矿物"管，
         * 石头就按矿物来解释。没有这一条，作者那次"石头底座 + 矿物模型"的实验永远不可能有反应
         * （石头不是矿物概念的成员）。
         *
         * @param climb 允许目标比源<b>高一档</b>吗？
         *              <ul>
         *                <li>{@code true}——<b>只有点火那一次</b>：石头要能被解释成铁矿，
         *                    所以池子取"恰好高一档"的概念成员。这一档是火给的凭据，
         *                    也是台阶链"一次火一档"的唯一来源。</li>
         *                <li>{@code false}——<b>点火之后的持续阶段</b>：池子取"源自己那一档"。
         *                    理由见 {@link #climb()}：否则挖走刚写出来的铁矿就能拿到下一档，
         *                    一次点火会变成一台升级机。</li>
         *              </ul>
         */
        public GuidedBias biasFor(MutationIndex index, BlockState source, boolean climb) {
            if (concept.isEmpty() || q <= 0.0 || index == null) {
                return GuidedBias.NONE;
            }
            int sourceTier = index.tier(source.getBlock());
            int targetTier = climb
                    ? Math.min(sourceTier + 1, Tiers.MAX_NATURAL_TIER)
                    : Math.min(sourceTier, Tiers.MAX_NATURAL_TIER);
            ClassifiedPool tiered = index.taggedAtTier(concept, targetTier);
            return new GuidedBias(tiered.isEmpty() ? index.tagged(concept) : tiered, q, climb);
        }

        /**
         * 这个位置此刻受到的<b>持续</b>催化：域内必中、域外一圈是代价、更远什么都没有。
         * <p>
         * <b>域内为什么是必中</b>（2026-09-29 第三轮实机反馈后<b>恢复</b>）：
         * 曾经有一版把域内改成"点火即写入、之后完全安静"，理由是两点——
         * "每挖一块再升一档"和"整片每周期重抽"。作者实机一句话推翻了它：
         * <b>"发生失焦的方块明显变少了"</b>。这两条理由里，前一条的真凶是越级
         * （现在由 {@link #climb()} 单独处理），后一条根本就是这个 mod 的本来形态
         * （整个世界每 5 秒重抽一次）——用"删掉功能"去解决它们，
         * 等于把催化剂退化成一次性写入器 + 一个看不见的惩罚。
         * <p>
         * <b>域内的必中必须配概念落点</b>：没有引导时命中按局部/大池抽，
         * tier 护栏只保证"不升"，一块铁矿可以变成石头——点火刚写出来的矿会被下一次重抽吃掉。
         * 所以"必中"和"概念落点"是一件事的两半，见 {@link #biasAt}。
         * <p>
         * <b>域外的代价加在两处</b>（命中率 + 落点）：只加落点等于没有代价——
         * 命中率不变时，"25% → 50% 走大池"在 1% 的命中率下几乎看不见。
         */
        public Catalysis at(BlockPos pos, long period) {
            if (!isActive(period)) {
                return NONE;
            }
            int distance = distance(center, pos);
            if (distance <= radius) {
                return new Catalysis(true, 0.0, 0.0, false);
            }
            if (distance <= radius + ringWidth) {
                return new Catalysis(false, AB_NO_SPILL_CHANCE ? 0.0 : spill, spill, false);
            }
            return NONE;
        }
    }

    /**
     * 多片域重叠时的合并规则（<b>纯函数，且与列表顺序无关</b>）：命中任何一片的必中区就是必中，
     * 否则取最大的两项增量；越级许可取"任何一片给了许可"。
     * <p>
     * 顺序无关是硬要求：服务端按登记顺序遍历、客户端按"整表 + 增量到达顺序"遍历，
     * 两种顺序不保证一致（与 {@code GuidedConcept#betterGuided} 同一条理由）。
     */
    public static Catalysis at(List<Field> fields, BlockPos pos, long period) {
        boolean anyForced = false;
        boolean anyClimb = false;
        double bestChance = 0.0;
        double bestWild = 0.0;
        for (Field field : fields) {
            Catalysis one = field.at(pos, period);
            anyForced |= one.forced();
            anyClimb |= one.climb();
            if (one.chanceBonus() > bestChance) {
                bestChance = one.chanceBonus();
            }
            if (one.wildBonus() > bestWild) {
                bestWild = one.wildBonus();
            }
        }
        return anyForced || bestChance > 0.0 || bestWild > 0.0
                ? new Catalysis(anyForced, bestChance, bestWild, anyClimb)
                : NONE;
    }

    /**
     * 多片域里取"概念偏向"最强的一片（{@code q} 大者胜，并列按中心坐标字典序——
     * 与 {@code GuidedConcept#betterGuided} 同一条理由：两端的列表顺序不保证一致）。
     * 没有任何一片带概念时返回 {@link GuidedBias#NONE}。
     * <p>
     * <b>只有必中区才有偏向</b>：spill 圈是"你控制不了的那一圈"，
     * 给它引导等于把代价变成了奖励。判据因此是 {@code forced()} 而不是 {@code isActive()}。
     * <p>
     * <b>越级一律不允许</b>（{@code climb = false}）：这是点火之后的持续阶段，
     * 那一档的许可只在点火那一刻发出去。
     */
    public static GuidedBias biasAt(List<Field> fields, BlockPos pos, long period,
                                    MutationIndex index, BlockState source) {
        Field best = null;
        for (Field field : fields) {
            if (field.concept().isEmpty() || field.q() <= 0.0) {
                continue;
            }
            if (!field.at(pos, period).forced()) {
                continue;
            }
            if (best == null || field.q() > best.q()
                    || (field.q() == best.q() && compareCenters(field.center(), best.center()) < 0)) {
                best = field;
            }
        }
        return best == null ? GuidedBias.NONE : best.biasFor(index, source, false);
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
}
