package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.ShapeClasses;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 确定性随机与目标计算（设计大纲 §5）。服务端与客户端使用相同种子与相同查表，保证两侧结果一致。
 * <p>
 * 2026-09-15 重写：池参数由"一个全局 {@code List<Block>}"换成预计算的
 * {@link MutationIndex}，源门控从"逐状态判定"换成一次数组读，随机源从
 * {@code RandomSource.create} 换成无分配的 {@link MutationRandom}。
 * <p>
 * <b>抽取规则</b>（{@link #resolve}，两端逐字共用）：
 * <ol>
 *   <li>保护：硬保护直接原样返回；软保护每个周期先掷"失守"骰；</li>
 *   <li>诞生周期：玩家放置/转换过的方块从"诞生周期 + 1"才开始崩坏；</li>
 *   <li>源门控：{@link MutationIndex#isSource}（不在任何可用池切片里、形态类为
 *       {@link ShapeClasses#NONE}、或命中 {@code mutation_immune} 的方块永不失焦），
 *       外加"含水状态不参与"（见下）；</li>
 *   <li>从当前周期向前回扫，找最近一次"抽中"的周期（上限 {@value #CUMULATIVE_SCAN_CAP} 个周期）；</li>
 *   <li>命中的周期里选池：引导偏向（概率 q 走概念池）→ 否则以 {@code wild_chance} 掷大池、
 *       其余走"本方块所属全部语义池的并集"；两者都只有一个可用时直接用它，不消耗随机步；</li>
 *   <li>在选中池里按<b>源方块的形态类</b>取候选，用确定性索引选中，再做状态迁移。</li>
 * </ol>
 * 每一步消耗的随机步数都是 (源方块, 形态类, 配置) 的纯函数，所以两端永远同步推进；
 * 唯一要求是 {@code wild_chance} 这类配置在两端一致（SERVER 配置会同步到客户端）。
 */
public final class MutationHelper {

    /**
     * 累积转换回退扫描的上限（周期数）。超出视为"从未抽中"，概率上已可忽略。
     * <p>
     * 这个常数同时是<b>出生周期记录的剪枝地平线</b>：比"当前周期 − 该上限"更早的诞生记录
     * 对结果没有任何影响（扫描本来就够不到），因此可以从持久化表里安全删除。
     */
    public static final int CUMULATIVE_SCAN_CAP = 128;

    private MutationHelper() {
    }

    /**
     * 位置保护形态（里程碑 7，2026-08-21）：
     * <ul>
     *   <li>{@code hard=true}：硬保护（生物稳定/完全稳定/阶段1-2语义锁定），完全不受失焦影响；</li>
     *   <li>{@code hard=false && softChance>0}：阶段3语义锁定的软保护——每周期先掷"失守"骰子，
     *       失守（1−softChance）才继续参与突变骰，未失守保持原方块；</li>
     *   <li>其余：无保护。</li>
     * </ul>
     */
    public record Protection(boolean hard, double softChance) {
        public static final Protection NONE = new Protection(false, 0.0);
        public static final Protection HARD = new Protection(true, 0.0);

        public boolean active() {
            return hard || softChance > 0.0;
        }
    }

    /**
     * 统一的方块识别函数：生存破坏、创造中键选取、右键交互、渲染预览、锚固化共用。
     * 受硬保护的位置、非转换源、以及没抽中的情况都返回原方块。
     *
     * @param index       预计算的突变查表（本维度的语义池 / 大池 / 源门控 / 形态类）
     * @param chance      当前阶段的每周期命中概率
     * @param bias        引导偏向（引导模型的概念邻域）
     * @param protection  保护形态
     * @param birthPeriod 诞生周期；{@code < 0} 表示世界原生方块
     */
    public static BlockState resolve(BlockState source, BlockPos pos, long worldSeed, long periodIndex,
                                     MutationIndex index, double chance, GuidedBias bias,
                                     Protection protection, long birthPeriod) {
        if (protection.hard() || chance <= 0.0 || index == null || index.isEmpty()) {
            return source;
        }
        // 玩家放置/转换过的方块：从"诞生周期 + 1"才开始崩坏，放置瞬间保持原方块。
        long fromPeriod = birthPeriod >= 0 ? birthPeriod + 1 : 0;
        if (periodIndex < fromPeriod) {
            return source;
        }

        Block sourceBlock = source.getBlock();
        if (!index.isSource(sourceBlock)) {
            return source;
        }
        // 含水方块（waterlogged）不参与失焦（2026-09-16）。
        // 看到的表象是"那一格的水不见了"：幽灵替换会把方块状态整个换掉，而 SectionCompiler 的
        // 流体渲染读的正是被替换后的状态（`blockstate.getFluidState()`），干燥幽灵的流体为空，
        // 于是水面出现一个 1 格缺口。同属"幽灵状态泄漏到本该用真实状态的环节"。
        //
        // 按状态而不是按方块排除是必须的：StairBlock / SlabBlock / FenceBlock / WallBlock /
        // TrapDoorBlock / IronBarsBlock 全都实现了 waterlogged，按方块排除等于整个形态类功能作废。
        // 用 getFluidState().isEmpty() 而不是直接查 waterlogged 属性，是为了把"任何带流体的状态"
        // （含模组方块）一并覆盖。
        if (!source.getFluidState().isEmpty()) {
            return source;
        }
        int shapeClass = index.shapeClass(sourceBlock);
        if (shapeClass == ShapeClasses.NONE) {
            return source;
        }

        ClassifiedPool wild = index.wild();
        int localCount = index.localCount(sourceBlock);
        int wildCount = wild.count(shapeClass);
        if (localCount == 0 && wildCount == 0) {
            return source;
        }
        double wildChance = FocalDecayConfig.WILD_CHANCE.get();
        double softChance = protection.softChance();
        boolean biased = bias != null && bias.active(shapeClass);
        int conceptCount = biased ? bias.pool().count(shapeClass) : 0;

        long span = periodIndex - fromPeriod + 1;
        int cap = (int) Math.min(span, CUMULATIVE_SCAN_CAP);
        for (int back = 0; back < cap; back++) {
            long period = periodIndex - back;
            long state = MutationRandom.seed(pos, worldSeed, period);

            if (softChance > 0.0) {
                state = MutationRandom.next(state);
                if (MutationRandom.toDouble(state) < softChance) {
                    continue; // 该周期被语义锁定稳定住，保持原方块并继续回扫
                }
            }
            state = MutationRandom.next(state);
            if (MutationRandom.toDouble(state) >= chance) {
                continue; // 该周期没抽中
            }

            // 命中：先看引导偏向，再在"局部并集 / 大池"之间二选一。
            ClassifiedPool tagPool = null;
            boolean useLocal = false;
            if (biased) {
                state = MutationRandom.next(state);
                if (MutationRandom.toDouble(state) < bias.q()) {
                    tagPool = bias.pool();
                }
            }
            if (tagPool == null) {
                if (localCount > 0 && wildCount > 0) {
                    state = MutationRandom.next(state);
                    useLocal = MutationRandom.toDouble(state) >= wildChance;
                } else {
                    // 只有一边可用时直接用它：不消耗随机步，但这条规则本身是纯函数，两端一致。
                    useLocal = localCount > 0;
                }
            }

            state = MutationRandom.next(state);
            Block target;
            if (tagPool != null) {
                target = tagPool.get(shapeClass, MutationRandom.nextInt(state, conceptCount));
            } else if (useLocal) {
                target = index.local(sourceBlock, MutationRandom.nextInt(state, localCount));
            } else {
                target = wild.get(shapeClass, MutationRandom.nextInt(state, wildCount));
            }
            // 抽到自己也是合法结果（自环），与原实现的"抽中即定格"语义一致，不再重抽。
            return MutationStateMapper.get().map(source, target);
        }
        return source;
    }

    /** SplitMix64 雪崩混合（保留旧入口，王座结构与 tools/structuregen/ThronePos.java 依赖它）。 */
    public static long mix64(long z) {
        return MutationRandom.mix64(z);
    }

    /** 计算种子（供外部复用的确定性随机源）。 */
    public static long seed(BlockPos pos, long worldSeed, long periodIndex) {
        return MutationRandom.seed(pos, worldSeed, periodIndex);
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

    /** 当前周期索引：gameTick / conversionInterval。 */
    public static long periodIndex(long gameTick, long conversionInterval) {
        return gameTick / Math.max(1L, conversionInterval);
    }

    /**
     * 方块突变的周期基准（**存储时钟**）：固定为 base_interval，与阶段、与调试倍率都无关。
     * <p>
     * 旧实现用各阶段 interval（100/60/40）计算周期，阶段切换时周期编号跳变，
     * 导致全图方块目标瞬间重排；固定基准后阶段切换只改变概率门控与影响范围。
     * <p>
     * <b>出生周期用的是这一支，不是 {@link #displayPeriod}</b>：出生周期记录的是"真实时间轴上
     * 这个方块何时出现"，只有存真实轴，"回滚之后后来放置的方块显示为原样"才有意义。
     */
    public static long blockPeriod(long gameTick) {
        return gameTick / Math.max(1L, FocalDecayConfig.BASE_INTERVAL.get());
    }

    /**
     * 失焦解析用的周期（**显示时钟**，2026-09-16）：带调试倍率与偏移，
     * 也就是 {@code /focaldecay period} 能拨动的那根指针。
     * <p>
     * <pre>
     *   scaled = floor(gameTick * speed)
     *   period = floorDiv(scaled, base_interval) + offset
     * </pre>
     * <b>先乘后除是刻意的</b>：{@code speed = 1.0} 时 {@code floor(gameTick * 1.0) == gameTick}
     * （gameTick 远小于 2^53，double 精确），于是 {code floorDiv(gameTick, interval)} 与
     * {@link #blockPeriod} 逐位相同 —— 默认档位下这个函数<b>完全不改变现有行为</b>。
     * 如果写成 {@code floor(gameTick * speed / interval)}，浮点除法会在"整除边界"上有少 1 的风险。
     * <p>
     * 用 {@link Math#floorDiv} 而不是 {@code /}：负倍率（倒带）时除法要向负无穷取整，
     * 否则 {@code -250/100} 会被截断成 {@code -2} 而不是 {@code -3}，倒带会出现台阶。
     * 正值下两者相同，所以不影响默认档位。
     *
     * @param speed  流速倍率：1 = 正常，0 = 冻结（配合 offset 逐刻步进），负 = 倒带，&gt;1 = 加速
     * @param offset 偏移（刻）：负数 = 回滚
     */
    public static long displayPeriod(long gameTick, double speed, long offset) {
        long scaled = (long) Math.floor(gameTick * speed);
        return Math.floorDiv(scaled, Math.max(1L, FocalDecayConfig.BASE_INTERVAL.get())) + offset;
    }
}
