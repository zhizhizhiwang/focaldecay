package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationStateMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 突变查表的自检（2026-09-15，{@code /focaldecay mutation audit}）。
 * <p>
 * 存在的理由：整套"对称、永不冻结、形态类内互变"的性质是<b>结构保证</b>的，
 * 但保证的前提是构建器的那条不变式（"池 × 形态类切片成员少于 2 个就作废"）真的生效了。
 * 数据包可以任意改动标签，所以需要一把能随时量的尺子，而不是靠读代码相信它。
 * <p>
 * 检查项：
 * <ol>
 *   <li><b>无吸收态</b>：任何能作为目标出现的方块，其候选集（局部并集 ∪ 大池同形态类）至少 2 个。
 *       不满足就意味着世界上会出现"变过去就再也变不走"的永久冻结方块；</li>
 *   <li><b>对称</b>：局部关系 A→B 必然伴随 B→A；</li>
 *   <li><b>形态类闭合</b>：任何目标与源的形态类相同（几何不会被破坏）。</li>
 * </ol>
 */
public final class MutationAudit {

    private MutationAudit() {
    }

    /** 全量自检，返回若干行可直接发给命令执行者的文本。 */
    public static List<String> audit(MutationIndex index) {
        List<String> lines = new ArrayList<>();
        if (index.isEmpty()) {
            lines.add("[mutation] index empty (tags not loaded yet?)");
            return lines;
        }
        ShapeClasses shapes = index.shapeClasses();
        var registry = BuiltInRegistries.BLOCK;

        lines.add("[mutation] pools=" + index.poolTagIds().size()
                + " wild=" + index.wild().total()
                + " shapeClasses=" + (shapes.count() - ShapeClasses.FIRST_CUSTOM)
                + " immune=" + index.immune().size());

        // 形态类成员统计
        TreeMap<String, Integer> perClass = new TreeMap<>();
        for (Block block : registry) {
            int shapeClass = index.shapeClass(block);
            if (shapeClass == ShapeClasses.NONE) {
                continue;
            }
            perClass.merge(shapes.name(shapeClass), 1, Integer::sum);
        }
        lines.add("[mutation] shape classes: " + perClass);

        int sources = 0;
        for (Block block : registry) {
            if (index.isSource(block)) {
                sources++;
            }
        }
        lines.add("[mutation] sources=" + sources + " (of " + registry.size() + " blocks)");

        // 候选集与三类检查
        int localEdges = 0;
        int wildEdges = 0;
        int frozen = 0;
        int asymmetric = 0;
        int crossClass = 0;
        Set<Block> reachable = new HashSet<>();
        List<String> problems = new ArrayList<>();

        for (Block source : registry) {
            // 只有"源"才会产出目标：非源（豁免方块、带方块实体的方块、none 形态类）永远不会被抽到，
            // 把它们算进 reachable 只会得到"可达但不可作为源"的假阳性。
            if (!index.isSource(source)) {
                continue;
            }
            int sourceClass = index.shapeClass(source);
            if (sourceClass == ShapeClasses.NONE) {
                continue;
            }
            Block[] local = index.localCandidates(source);
            ClassifiedPool wild = index.wild();
            int wildCount = wild.count(sourceClass);
            if (local.length == 0 && wildCount == 0) {
                continue;
            }
            localEdges += local.length;
            wildEdges += wildCount;
            reachable.add(source);
            for (Block target : local) {
                reachable.add(target);
                if (index.shapeClass(target) != sourceClass) {
                    crossClass++;
                    if (problems.size() < 12) {
                        problems.add("cross-class: " + id(source) + " -> " + id(target));
                    }
                }
                if (!index.localContains(target, source) && wild.count(index.shapeClass(target)) == 0) {
                    asymmetric++;
                    if (problems.size() < 12) {
                        problems.add("asymmetric: " + id(source) + " -> " + id(target));
                    }
                }
            }
            for (int i = 0; i < wildCount; i++) {
                reachable.add(wild.get(sourceClass, i));
            }
        }

        for (Block block : reachable) {
            int shapeClass = index.shapeClass(block);
            Set<Block> reach = new HashSet<>();
            for (Block candidate : index.localCandidates(block)) {
                reach.add(candidate);
            }
            ClassifiedPool wild = index.wild();
            for (int i = 0; i < wild.count(shapeClass); i++) {
                reach.add(wild.get(shapeClass, i));
            }
            if (reach.size() < 2) {
                frozen++;
                if (problems.size() < 12) {
                    problems.add("frozen: " + id(block) + " candidates=" + reach.size());
                }
            }
        }

        lines.add("[mutation] reachable=" + reachable.size()
                + " localEdges=" + localEdges + " wildEdges=" + wildEdges);
        lines.add("[mutation] wild slice sizes: " + wildSlices(index));
        // 有源但没有入边的方块：只能变出去、不会被变回来。单向不等于冻结（它自己仍能继续变），
        // 但如果数量异常大，通常意味着池划分把某个形态族孤立了。
        int noInbound = 0;
        List<String> lonely = new ArrayList<>();
        for (Block block : registry) {
            if (!index.isSource(block)) {
                continue;
            }
            int shapeClass = index.shapeClass(block);
            boolean inbound = index.wild().count(shapeClass) >= 2;
            if (!inbound) {
                for (Block other : registry) {
                    if (index.isSource(other) && index.localContains(other, block)) {
                        inbound = true;
                        break;
                    }
                }
            }
            if (!inbound) {
                noInbound++;
                if (lonely.size() < 8) {
                    lonely.add(id(block));
                }
            }
        }
        lines.add("[mutation] sources with no inbound edge: " + noInbound + " " + lonely);
        lines.add("[mutation] checks: frozen=" + frozen
                + " asymmetric=" + asymmetric + " crossClass=" + crossClass
                + (frozen == 0 && asymmetric == 0 && crossClass == 0 ? "  OK" : "  FAIL"));
        lines.addAll(problems);
        return lines;
    }

    private static String id(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "?" : key.toString();
    }

    /** 每个形态类在大池里的切片大小：切片小于 2 的类不能靠大池兜底。 */
    private static String wildSlices(MutationIndex index) {
        ShapeClasses shapes = index.shapeClasses();
        StringBuilder sb = new StringBuilder();
        for (int shapeClass = 0; shapeClass < shapes.count(); shapeClass++) {
            if (shapeClass == ShapeClasses.NONE) {
                continue;
            }
            sb.append(shapes.name(shapeClass)).append('=').append(index.wild().count(shapeClass)).append(' ');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // 运行期自测：把"设计上应当成立"的性质实测一遍
    // ------------------------------------------------------------------

    /** 分布自测的采样周期数（每个周期必中，所以样本数 = 该值）。 */
    private static final int SAMPLE_PERIODS = 256;

    /**
     * 无头服务器上的端到端自测（{@code /focaldecay mutation selftest}）。
     * 覆盖四条最容易在重构里悄悄坏掉的性质：确定性、不收敛、对称、状态迁移。
     */
    public static List<String> selfTest(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        MutationIndex index = MutationIndexes.get(level.dimension());
        if (index.isEmpty()) {
            out.add("[selftest] index empty (tags not loaded?)  FAIL");
            return out;
        }
        long seed = level.getSeed();

        // ---- 1. 确定性：同一 (位置, 周期, 源方块) 反复求值必须逐位相同 ----
        Block source = Blocks.STONE_BRICKS;
        BlockState first = sample(index, source, pos, seed, 12345L);
        boolean deterministic = true;
        for (int i = 0; i < 3; i++) {
            deterministic &= sample(index, source, pos, seed, 12345L) == first;
        }
        out.add("[selftest] determinism (3 repeats): " + (deterministic ? "PASS" : "FAIL"));

        // ---- 2. 不收敛：固定位置扫一批周期，目标必须持续变化 ----
        Set<Block> distinct = new HashSet<>();
        for (long period = 0; period < SAMPLE_PERIODS; period++) {
            distinct.add(sample(index, source, pos, seed, period).getBlock());
        }
        double share = distinct.isEmpty() ? 1.0 : 1.0 / distinct.size();
        out.add("[selftest] distribution over " + SAMPLE_PERIODS + " periods: distinct=" + distinct.size()
                + " (source=" + id(source) + ")  "
                + (distinct.size() >= 4 ? "PASS" : "FAIL"));
        out.add("[selftest]   largest expected share ~" + Math.round(share * 100) + "%");

        // ---- 3. 对称：每个实测目标都能反过来变回源方块（或源就在大池的同形态类里）----
        int asymmetric = 0;
        List<String> bad = new ArrayList<>();
        for (Block target : distinct) {
            int targetClass = index.shapeClass(target);
            boolean back = index.localContains(target, source) || index.wild().count(targetClass) > 0;
            if (!back) {
                asymmetric++;
                if (bad.size() < 6) {
                    bad.add("[selftest]   asymmetric: " + id(source) + " -> " + id(target));
                }
            }
        }
        out.add("[selftest] symmetry: " + (asymmetric == 0 ? "PASS" : "FAIL (" + asymmetric + ")"));
        out.addAll(bad);

        // ---- 4. 形态类 + 状态迁移：楼梯只变楼梯，且朝向/上下半/形状跟着搬过去 ----
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        int stairsClass = index.shapeClass(stairs.getBlock());
        BlockState mapped = MutationStateMapper.get().map(stairs, Blocks.STONE_STAIRS);
        boolean shapeOk = index.shapeClass(mapped.getBlock()) == stairsClass
                && mapped.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST
                && mapped.getValue(BlockStateProperties.HALF) == Half.TOP
                && mapped.getValue(BlockStateProperties.STAIRS_SHAPE) == StairsShape.INNER_LEFT;
        boolean dry = !mapped.getValue(BlockStateProperties.WATERLOGGED);
        out.add("[selftest] state transfer (facing/half/shape kept): " + (shapeOk ? "PASS" : "FAIL " + mapped));
        out.add("[selftest] waterlogged dropped: " + (dry ? "PASS" : "FAIL"));

        int strayStairs = 0;
        for (long period = 0; period < SAMPLE_PERIODS; period++) {
            BlockState target = sample(index, Blocks.OAK_STAIRS, pos, seed, period);
            if (index.shapeClass(target.getBlock()) != stairsClass) {
                strayStairs++;
            }
        }
        out.add("[selftest] stairs stay stairs over " + SAMPLE_PERIODS + " periods: "
                + (strayStairs == 0 ? "PASS" : "FAIL (" + strayStairs + " strays)"));

        // ---- 5. 含水状态不参与失焦 ----
        // 幽灵替换会把整格状态换掉，而流体渲染读的是替换后的状态，于是那一格的水会消失。
        // 按状态（而不是按方块）排除是必须的：楼梯/半砖/栏杆全都实现了 waterlogged。
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState wetSamePeriod = sample(index, wet, pos, seed, 4242L);
        out.add("[selftest] waterlogged never mutates (chance=1 forced): "
                + (wetSamePeriod == wet ? "PASS" : "FAIL -> " + wetSamePeriod));
        BlockState dryStairs = Blocks.OAK_STAIRS.defaultBlockState();
        out.add("[selftest] same stairs still mutate when dry: "
                + (sample(index, dryStairs, pos, seed, 4242L).getBlock() != dryStairs.getBlock() ? "PASS" : "FAIL"));

        // ---- 5. 热路径开销：客户端每 2 帧要对可见范围内所有方块跑一遍这个函数 ----
        out.add("[selftest] hot path (ns per resolve, " + BENCH_ITERATIONS + " iterations):");
        out.add("[selftest]   chance=1.00 (1 scan step):  "
                + bench(index, source, pos, seed, 1.0) + " ns");
        out.add("[selftest]   chance=0.01 (stage 1, ~100 steps): "
                + bench(index, source, pos, seed, 0.01) + " ns");
        out.add("[selftest]   chance=0.01, stairs + state transfer: "
                + bench(index, Blocks.OAK_STAIRS, pos, seed, 0.01) + " ns");
        return out;
    }

    /** 基准采样次数。 */
    private static final int BENCH_ITERATIONS = 1_000_000;

    /** 单点热路径耗时；返回值用异或累加防止整段被优化掉。 */
    private static long bench(MutationIndex index, Block source, BlockPos pos, long seed, double chance) {
        int sink = 0;
        for (int i = 0; i < BENCH_ITERATIONS / 4; i++) {
            sink ^= Block.getId(MutationHelper.resolve(source.defaultBlockState(), pos, seed, i, index, chance,
                    GuidedBias.NONE, MutationHelper.Protection.NONE, -1L));
        }
        long start = System.nanoTime();
        for (int i = 0; i < BENCH_ITERATIONS; i++) {
            sink ^= Block.getId(MutationHelper.resolve(source.defaultBlockState(), pos, seed, i, index, chance,
                    GuidedBias.NONE, MutationHelper.Protection.NONE, -1L));
        }
        long elapsed = System.nanoTime() - start;
        if (sink == 0x7FFFFFFF) {
            return -1; // 不可达，仅用于让 sink 逃逸
        }
        return elapsed / BENCH_ITERATIONS;
    }

    /** 以 chance = 1 抽取一个周期（必中，因此样本数与周期数相等）。 */
    private static BlockState sample(MutationIndex index, Block source, BlockPos pos, long seed, long period) {
        return sample(index, source.defaultBlockState(), pos, seed, period);
    }

    /** 同上，但直接给状态（用于带属性的样本，例如含水楼梯）。 */
    private static BlockState sample(MutationIndex index, BlockState source, BlockPos pos, long seed, long period) {
        return MutationHelper.resolve(source, pos, seed, period, index, 1.0,
                GuidedBias.NONE, MutationHelper.Protection.NONE, -1L);
    }
}
