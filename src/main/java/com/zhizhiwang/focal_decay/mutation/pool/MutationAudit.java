package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.InteractionHandler;
import com.zhizhiwang.focal_decay.mutation.MutationEventHandler;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
import com.zhizhiwang.focal_decay.mutation.MutationStateMapper;
import com.zhizhiwang.focal_decay.network.SyncClientViewPacket;
import com.zhizhiwang.focal_decay.network.SyncMutationSettingsPacket;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
        out.addAll(mapperStressTest(index));
        out.addAll(syncSelfTest(level, pos));
        return out;
    }

    /** 基准采样次数。 */
    private static final int BENCH_ITERATIONS = 1_000_000;

    // ------------------------------------------------------------------
    // 两端输入一致性
    // ------------------------------------------------------------------

    /** 一致性采样的周期数。 */
    private static final int SYNC_SAMPLE_PERIODS = 64;

    /**
     * 两端输入一致性自测（2026-09-17，起因是一次真实联机 bug：局域网里两个人看到的方块不一样，
     * 挖下去掉落也对不上）。
     * <p>
     * 根因不是解析函数有分歧——那个函数两端共用——而是它<b>吃到了不同的输入</b>：
     * 客户端在多人模式下拿不到世界种子（1.21 的登录包只给哈希种子），退回 {@code 0}；
     * 而 {@code wild_chance} 这类 SERVER 配置客户端读的是自己那份 toml。
     * 修法是让服务端把输入整份发下来（{@link com.zhizhiwang.focal_decay.network.SyncMutationSettingsPacket}）。
     * <p>
     * 于是有两条必须钉住的性质，缺一条修复就不成立：
     * <ol>
     *   <li><b>线格式无损</b>：快照经过编码/解码必须逐字段相同。手写的 13 字段编解码最容易出的错
     *       就是字段串位——种子被读成周期长度之类，而且完全静默；</li>
     *   <li><b>两条入口等价</b>：服务端入口（读本端配置）与客户端入口（用同步来的快照），
     *       在同一份配置下必须给出<b>逐位相同</b>的方块状态。这一条也是这次重构的回归网：
     *       把 {@code resolve} 里任何一个参数接错线，这里立刻红。</li>
     * </ol>
     */
    public static List<String> syncSelfTest(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        MutationIndex index = MutationIndexes.get(level.dimension());
        if (index.isEmpty()) {
            out.add("[sync] index empty (tags not loaded?)  FAIL");
            return out;
        }
        long seed = level.getSeed();
        MutationSettings settings = MutationSettings.fromConfig(seed);

        // ---- 1. 快照的线格式必须逐字段往返 ----
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncMutationSettingsPacket.STREAM_CODEC.encode(buf, new SyncMutationSettingsPacket(settings));
        MutationSettings decoded = SyncMutationSettingsPacket.STREAM_CODEC.decode(buf).settings();
        out.add("[sync] settings packet round-trip (13 fields): "
                + (decoded.equals(settings) ? "PASS" : "FAIL\n  sent=" + settings + "\n  read=" + decoded));

        // 原型机摘要也走手写编解码（bioActive 由 int 改成 boolean 时最容易串位）
        SyncRegionDataPacket.PrototypeData prototype = new SyncRegionDataPacket.PrototypeData(
                4321L, 8, ObserverModelData.TYPE_BIO, List.of("minecraft:stone", "minecraft:oak_log"),
                List.of("minecraft:pig"), true, "focal_decay:concept/stone", 5, 0.75, 2);
        FriendlyByteBuf pbuf = new FriendlyByteBuf(Unpooled.buffer());
        SyncRegionDataPacket.PrototypeData.STREAM_CODEC.encode(pbuf, prototype);
        SyncRegionDataPacket.PrototypeData prototypeRead =
                SyncRegionDataPacket.PrototypeData.STREAM_CODEC.decode(pbuf);
        out.add("[sync] prototype packet round-trip: "
                + (prototypeRead.equals(prototype) ? "PASS" : "FAIL -> " + prototypeRead));

        // ---- 2. 客户端入口（快照）必须与服务端入口（本端配置）给出同一个目标 ----
        int mismatch = 0;
        String firstMismatch = "";
        List<Block> sources = List.of(Blocks.STONE_BRICKS, Blocks.OAK_STAIRS, Blocks.OAK_LOG,
                Blocks.STONE_SLAB, Blocks.OAK_FENCE);
        for (Block source : sources) {
            BlockState state = source.defaultBlockState();
            for (int stage = 1; stage <= 3; stage++) {
                double chance = MutationHelper.mutationChance(stage);
                for (long period = 0; period < SYNC_SAMPLE_PERIODS; period++) {
                    BlockState viaConfig = MutationHelper.resolve(state, pos, seed, period, index, chance,
                            GuidedBias.NONE, MutationHelper.Protection.NONE, -1L);
                    BlockState viaSnapshot = MutationHelper.resolve(state, pos, settings, stage, period, index,
                            GuidedBias.NONE, MutationHelper.Protection.NONE, -1L);
                    if (viaConfig != viaSnapshot) {
                        mismatch++;
                        if (firstMismatch.isEmpty()) {
                            firstMismatch = "  " + id(source) + " stage=" + stage + " period=" + period
                                    + ": config=" + viaConfig + " snapshot=" + viaSnapshot;
                        }
                    }
                }
            }
        }
        out.add("[sync] client entry (snapshot) == server entry (config) over "
                + (sources.size() * 3 * SYNC_SAMPLE_PERIODS) + " samples: "
                + (mismatch == 0 ? "PASS" : "FAIL (" + mismatch + ")\n" + firstMismatch));

        // ---- 3. 阶段/时钟/训练点数：快照里的换算必须与本端配置一致 ----
        boolean stageOk = true;
        boolean clockOk = true;
        boolean pointsOk = true;
        for (long days = 0; days <= 10; days++) {
            stageOk &= settings.stage(days) == MutationHelper.currentStage(days);
        }
        for (long tick = 0; tick < 5000; tick += 137) {
            clockOk &= settings.displayPeriod(tick, 1.0, 0L) == MutationHelper.displayPeriod(tick, 1.0, 0L);
            clockOk &= settings.storagePeriod(tick) == MutationHelper.blockPeriod(tick);
        }
        for (int copies = 0; copies <= 3; copies++) {
            pointsOk &= settings.requiredCandidatePoints(copies)
                    == ObserverModelData.requiredCandidatePoints(copies);
        }
        out.add("[sync] stage / clock / candidate-points parity: "
                + ((stageOk && clockOk && pointsOk) ? "PASS"
                : "FAIL stage=" + stageOk + " clock=" + clockOk + " points=" + pointsOk));

        // ---- 4. 客户端回报的显示刻必须被夹在"物理可能"的范围内 ----
        // 这是交互路径唯一的客户端输入，所以它必须只放行"时钟自走能造成的偏差"，
        // 而不是任何客户端说什么就是什么。界内取值由 MAX_CLIENT_SKEW_TICKS 换算而来：
        //   bound = ceil(|speed| × 100 / interval) + 1
        // interval = 100 时：speed 1 → 2；speed 0（冻结）→ 1；speed 64 → 27。
        long interval = Math.max(1L, MutationHelper.configBaseInterval());
        long bound1 = (long) Math.ceil(InteractionHandler.MAX_CLIENT_SKEW_TICKS / (double) interval) + 1;        boolean skewOk = true;
        String skewDetail = "";
        skewOk &= MutationHelper.configBaseInterval() > 0; // interval 为 0 会让界变成无穷大
        for (long delta : new long[]{-bound1, 0L, bound1}) {
            boolean ok = InteractionHandler.periodWithinSkew(1000L, 1000L + delta, 1.0, interval);
            skewOk &= ok;
            if (!ok && skewDetail.isEmpty()) {
                skewDetail = "  speed 1 delta=" + delta + " should be accepted";
            }
        }
        for (long delta : new long[]{-bound1 - 1, bound1 + 1}) {
            boolean ok = !InteractionHandler.periodWithinSkew(1000L, 1000L + delta, 1.0, interval);
            skewOk &= ok;
            if (!ok && skewDetail.isEmpty()) {
                skewDetail = "  speed 1 delta=" + delta + " should be rejected";
            }
        }
        // 冻结档（speed = 0）：两端都取 offset，偏差只可能是取整余量
        skewOk &= InteractionHandler.periodWithinSkew(1000L, 1001L, 0.0, interval);
        skewOk &= !InteractionHandler.periodWithinSkew(1000L, 1002L, 0.0, interval);
        // 高速档（speed = 64）：同样的刻偏差能跨过更多周期，界必须跟着放大
        long bound64 = (long) Math.ceil(64.0 * InteractionHandler.MAX_CLIENT_SKEW_TICKS / interval) + 1;
        skewOk &= InteractionHandler.periodWithinSkew(1000L, 1000L + bound64, 64.0, interval);
        skewOk &= !InteractionHandler.periodWithinSkew(1000L, 1000L + bound64 + 1, 64.0, interval);
        // 谎报：荒唐值必须被拒；这里同时是"别用 Math.abs 做减法"的回归（会溢出成负数而误判通过）
        skewOk &= !InteractionHandler.periodWithinSkew(1000L, Long.MAX_VALUE, 1.0, interval);
        skewOk &= !InteractionHandler.periodWithinSkew(1000L, Long.MIN_VALUE, 1.0, interval);
        out.add("[sync] client view echo accepted only within a physically possible skew: "
                + (skewOk ? "PASS" : "FAIL (bound=" + bound1 + ", speed64 bound=" + bound64 + ")\n"
                + skewDetail));

        // ---- 5. 回报的取舍规则 + 线格式 ----
        boolean chooseOk = true;
        long server = 1000L;
        // 位置对 + 够新 + 偏差在界内 → 采用客户端的
        chooseOk &= InteractionHandler.chooseInteractionPeriod(server, server + bound1, 1L, true, 1.0, interval)
                == server + bound1;
        // 位置不符 / 过期 / 未来时间戳 → 一律退回服务端
        chooseOk &= InteractionHandler.chooseInteractionPeriod(server, server + 1, 1L, false, 1.0, interval) == server;
        chooseOk &= InteractionHandler.chooseInteractionPeriod(server, server + 1,
                InteractionHandler.CLIENT_VIEW_TTL_TICKS + 1L, true, 1.0, interval) == server;
        chooseOk &= InteractionHandler.chooseInteractionPeriod(server, server + 1, -5L, true, 1.0, interval) == server;
        // 偏差超出物理可能 → 退回服务端
        chooseOk &= InteractionHandler.chooseInteractionPeriod(server, server + bound1 + 1, 1L, true, 1.0, interval)
                == server;
        out.add("[sync] client view echo decision (pos / age / skew): " + (chooseOk ? "PASS" : "FAIL"));

        SyncClientViewPacket viewPacket = new SyncClientViewPacket(123456789L, 4242L);
        FriendlyByteBuf vbuf = new FriendlyByteBuf(Unpooled.buffer());
        SyncClientViewPacket.STREAM_CODEC.encode(vbuf, viewPacket);
        out.add("[sync] client view packet round-trip: "
                + (SyncClientViewPacket.STREAM_CODEC.decode(vbuf).equals(viewPacket) ? "PASS" : "FAIL"));

        out.add(birthGateSelfTest(level, pos, seed, index));
        return out;
    }

    /**
     * 诞生周期闸门必须与调试倍率无关（2026-09-17 的真实 bug：右键长按把方块来回转换、刚放下的方块立刻失焦）。
     * <p>
     * 闸门是 {@code resolve} 里的 {@code periodIndex < birthPeriod + 1}，比的是<b>显示刻</b>。
     * 诞生周期曾经记在<b>存储刻</b>上，而 {@code /focaldecay period speed} 会让两者按倍率分家：
     * 倍率 7 时显示刻是存储刻的 7 倍，"刚出生"的方块一上来就满足 {@code >= birth + 1}，
     * 闸门形同不存在。
     * <p>
     * 三条断言，在 speed = 1 / 7 / 0.5 下各跑一遍：
     * <ol>
     *   <li>诞生那一刻（同一个显示刻）必须原样返回；</li>
     *   <li>过一个显示周期后必须能变（否则就是反向的 bug：方块永远冻住）；</li>
     *   <li><b>倍率 ≠ 1 时，用存储刻当诞生周期必须给出<b>不同</b>的判定</b>——这条是"测试确实能抓到那个 bug"
     *       的自证。两个方向都错：倍率 &gt; 1 时存储刻偏小、闸门一上来就开（方块立刻失焦、右键来回转换）；
     *       倍率 &lt; 1 时存储刻偏大、闸门迟迟不开（放下的方块长期不失焦）。</li>
     * </ol>
     */
    private static String birthGateSelfTest(ServerLevel level, BlockPos pos, long seed, MutationIndex index) {
        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        double savedSpeed = worldData.getClockSpeed();
        long savedOffset = worldData.getClockOffset();
        BlockState source = Blocks.STONE_BRICKS.defaultBlockState();
        boolean frozenOk = true;
        boolean mutatesOk = true;
        boolean discriminating = true;
        // 逐条记下失败原因：只留最后一条会把"倍率 7 时闸门根本没关"这种真正的病因盖掉
        List<String> failures = new ArrayList<>();
        try {
            for (double speed : new double[]{1.0, 7.0, 0.5}) {
                worldData.setClock(speed, 0L);
                long now = MutationEventHandler.displayPeriodIndex(level);
                long birth = MutationEventHandler.birthPeriodIndex(level, Long.MIN_VALUE);
                long wrongBirth = MutationEventHandler.storagePeriodIndex(level);

                if (MutationHelper.resolve(source, pos, seed, now, index, 1.0, GuidedBias.NONE,
                        MutationHelper.Protection.NONE, birth) != source) {
                    frozenOk = false;
                    failures.add("speed=" + speed + ": newborn block mutated right away");
                }
                // 抽到自己也是合法结果（自环），所以往后多看两个周期再判"能不能变"
                boolean mutates = false;
                boolean differs = false;
                for (long p = now; p <= now + 3; p++) {
                    BlockState good = MutationHelper.resolve(source, pos, seed, p, index, 1.0, GuidedBias.NONE,
                            MutationHelper.Protection.NONE, birth);
                    BlockState wrong = MutationHelper.resolve(source, pos, seed, p, index, 1.0, GuidedBias.NONE,
                            MutationHelper.Protection.NONE, wrongBirth);
                    mutates |= p > now && good != source;
                    differs |= good != wrong;
                }
                if (!mutates) {
                    mutatesOk = false;
                    failures.add("speed=" + speed + ": still frozen after three periods");
                }
                if (speed != 1.0 && !differs) {
                    discriminating = false;
                    failures.add("speed=" + speed + ": storage-clock birth gives the same verdict");
                }
            }
        } finally {
            worldData.setClock(savedSpeed, savedOffset);
        }
        return "[sync] birth gate (newborn frozen / next period mutates / storage clock would differ): "
                + ((frozenOk && mutatesOk && discriminating) ? "PASS" : "FAIL " + String.join("; ", failures));
    }

    // ------------------------------------------------------------------
    // 并发压力测试
    // ------------------------------------------------------------------

    /** 并发压力测试的等待上限（秒）。超时即判 FAIL——退化回共享缓存时线程会死转，不能无限等。 */
    private static final int STRESS_TIMEOUT_SECONDS = 10;

    /**
     * {@link MutationStateMapper} 的并发压力测试（2026-09-16 新增，起因是一次真实崩溃）。
     * <p>
     * 线上崩过：
     * <pre>
     *   ArrayIndexOutOfBoundsException: Index 8192 out of bounds for length 4097
     *     at Long2ObjectOpenHashMap.rehash -> put -> MutationStateMapper.map
     *     at SectionRenderDispatcher$RenderSection$RebuildTask.doTask   // ForkJoinPool worker
     * </pre>
     * 区块编译并发跑在 ForkJoinPool 上，而那个缓存当时是全局共享的非线程安全表。
     * 修法是把缓存改成每线程一份；这个测试就是钉住它：
     * <b>多线程并发调用必须既不抛异常，也不给出与单线程不同的结果</b>。
     * <p>
     * 之所以值得放进常规自检：这类错误只在多人/多核的真实编译负载下偶发，
     * 光看代码（"映射是纯函数，缓存不影响正确性"）很容易漏掉。
     */
    public static List<String> mapperStressTest(MutationIndex index) {
        List<String> out = new ArrayList<>();
        // 样本：带属性的方块状态 × 同形态类的候选。状态数够多，才能把哈希表撑到反复扩容。
        List<BlockState> sources = new ArrayList<>();
        for (Block block : new Block[]{
                Blocks.OAK_STAIRS, Blocks.OAK_LOG, Blocks.STONE_SLAB, Blocks.OAK_FENCE,
                Blocks.COBBLESTONE_WALL, Blocks.OAK_TRAPDOOR, Blocks.WHITE_CARPET,
                Blocks.GLASS_PANE, Blocks.OAK_FENCE_GATE, Blocks.WHITE_GLAZED_TERRACOTTA}) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                sources.add(state);
            }
        }
        if (sources.isEmpty()) {
            out.add("[stress] state mapper: no sample states  FAIL");
            return out;
        }

        List<BlockState> from = new ArrayList<>();
        List<Block> to = new ArrayList<>();
        for (BlockState source : sources) {
            int shapeClass = index.shapeClass(source.getBlock());
            Block[] local = index.localCandidates(source.getBlock());
            ClassifiedPool wild = index.wild();
            int wildCount = wild.count(shapeClass);
            int limit = Math.min(64, local.length + wildCount);
            for (int i = 0; i < limit; i++) {
                from.add(source);
                to.add(i < local.length ? local[i] : wild.get(shapeClass, i - local.length));
            }
        }
        if (from.isEmpty()) {
            out.add("[stress] state mapper: no sample pairs  FAIL");
            return out;
        }

        // 单线程参考结果：并发跑出来的必须逐位相同（映射是纯函数）
        BlockState[] expected = new BlockState[from.size()];
        for (int i = 0; i < from.size(); i++) {
            expected[i] = MutationStateMapper.get().map(from.get(i), to.get(i));
        }

        // 线程数与迭代量刻意开大：缓存是有上限的（超了会整体清空重来），
        // 只有"并发地不断插入新键 + 反复清空"才会真正踩到扩容/清空与插入交叠的窗口。
        // 规模太小的话（比如把键空间限制在缓存容量以内）测试会一片绿却什么都没验到——
        // 这一点是实测出来的：第一版压力测试就是这么假通过的。
        int threads = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
        int perThread = 40_000;
        java.util.concurrent.atomic.AtomicInteger mismatches = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] workers = new Thread[threads];
        long startedAt = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int index0 = t;
            workers[t] = new Thread(() -> {
                RandomSource random = RandomSource.create(index0 * 7919L + 13L);
                try {
                    for (int i = 0; i < perThread; i++) {
                        int slot = random.nextInt(from.size());
                        BlockState got = MutationStateMapper.get().map(from.get(slot), to.get(slot));
                        if (got != expected[slot]) {
                            mismatches.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "focaldecay-mapper-stress-" + t);
            // 守护线程：万一真的退化回"共享表被写坏"，fastutil 会在探测循环里死转，
            // 非守护线程会把 JVM 一起拖住不退出。
            workers[t].setDaemon(true);
            workers[t].start();
        }

        // 有上限地等待。**不能直接 join()**：表被写坏时线程会在
        // Long2ObjectOpenHashMap.find 的探测循环里永远转下去（实测过，会把服务器
        // 拖到看门狗超时被杀）。这里超时就报 FAIL，把"卡死"变成一个可读的失败信号。
        long deadline = startedAt + java.util.concurrent.TimeUnit.SECONDS.toNanos(STRESS_TIMEOUT_SECONDS);
        for (Thread worker : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                worker.join(Math.max(1L, remaining / 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int stuck = 0;
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                stuck++;
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (stuck > 0) {
            out.add("[stress] state mapper: " + threads + " threads x " + perThread + " ops over "
                    + from.size() + " pairs: FAIL (" + stuck + " thread(s) still spinning after "
                    + STRESS_TIMEOUT_SECONDS + "s)");
            out.add("[stress]   a corrupted shared cache spins forever in fastutil's probe loop;"
                    + " the cache must stay per-thread");
            return out;
        }

        Throwable thrown = failure.get();
        out.add("[stress] state mapper: " + threads + " threads x " + perThread
                + " ops over " + from.size() + " pairs: "
                + (thrown == null && mismatches.get() == 0 ? "PASS" : "FAIL")
                + " (" + elapsedMs + " ms)");
        if (thrown != null) {
            out.add("[stress]   threw " + thrown);
        }
        if (mismatches.get() != 0) {
            out.add("[stress]   " + mismatches.get() + " results differed from the single-threaded reference");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 调试时钟自测（/focaldecay period selftest）
    // ------------------------------------------------------------------

    /**
     * 失焦时钟的自测：把 {@code /focaldecay period} 的每一档都验一遍。
     * <p>
     * 分两类断言：
     * <ol>
     *   <li><b>管道正确性</b>（算术，精确）：默认档位下显示刻必须与存储刻<b>逐位相同</b>
     *       ——这是"加了调试功能但没有改变现有行为"的唯一证据；倍率/偏移/冻结各自映射到预期的刻。</li>
     *   <li><b>端到端</b>：冻结在当前刻时抽出来的目标，必须与不冻结时完全一致
     *       （冻结只是停住指针，不该改变指针指向的那一刻长什么样）；
     *       并且同一个过去刻重复访问给出同一个状态——这就是"回滚可寻址、可重放"。</li>
     * </ol>
     * 结束时一定恢复原档位（{@code finally}），否则会把世界留在冻结状态。
     */
    public static List<String> periodSelfTest(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        MutationIndex index = MutationIndexes.get(level.dimension());
        FocalDecayWorldData data = FocalDecayWorldData.get(level.getServer());
        double savedSpeed = data.getClockSpeed();
        long savedOffset = data.getClockOffset();
        long gameTime = level.getGameTime();
        long interval = Math.max(1L, FocalDecayConfig.BASE_INTERVAL.get());
        try {
            // ---- 1. 管道正确性 ----
            data.setClock(1.0, 0L);
            long storage = MutationEventHandler.storagePeriodIndex(level);
            long display = MutationEventHandler.displayPeriodIndex(level);
            out.add("[period] default clock is identity (display == storage): "
                    + (display == storage ? "PASS" : "FAIL " + display + " != " + storage));

            data.setClock(4.0, 0L);
            long fast = MutationEventHandler.displayPeriodIndex(level);
            long expectedFast = Math.floorDiv((long) Math.floor(gameTime * 4.0), interval);
            out.add("[period] speed 4 -> " + fast + " (expected " + expectedFast + "): "
                    + (fast == expectedFast ? "PASS" : "FAIL"));

            data.setClock(0.5, 0L);
            long slow = MutationEventHandler.displayPeriodIndex(level);
            long expectedSlow = Math.floorDiv((long) Math.floor(gameTime * 0.5), interval);
            out.add("[period] speed 0.5 -> " + slow + " (expected " + expectedSlow + "): "
                    + (slow == expectedSlow ? "PASS" : "FAIL"));

            long frozenAt = storage - 37;
            data.setClock(0.0, frozenAt);
            long frozen = MutationEventHandler.displayPeriodIndex(level);
            out.add("[period] speed 0 + offset " + frozenAt + " freezes at that period: "
                    + (frozen == frozenAt ? "PASS" : "FAIL -> " + frozen));

            long rewound = storage - 50;
            data.setClock(-1.0, rewound);
            long rewind = MutationEventHandler.displayPeriodIndex(level);
            out.add("[period] speed -1 rewinds (advances backwards): "
                    + (rewind <= rewound ? "PASS" : "FAIL -> " + rewind));

            // ---- 2. 端到端：冻结不改变"那一刻长什么样" ----
            Block source = Blocks.STONE_BRICKS;
            data.setClock(1.0, 0L);
            long livePeriod = MutationEventHandler.displayPeriodIndex(level);
            BlockState live = sample(index, source, pos, level.getSeed(), livePeriod);
            data.setClock(0.0, livePeriod);
            BlockState frozenLive = sample(index, source, pos, level.getSeed(),
                    MutationEventHandler.displayPeriodIndex(level));
            out.add("[period] freezing at the live period keeps the picture: "
                    + (frozenLive == live ? "PASS" : "FAIL " + live + " -> " + frozenLive));

            // ---- 3. 回滚可重放：同一个过去刻 → 同一个状态；不同刻能翻出不同世界 ----
            long past = livePeriod - 37;
            data.setClock(0.0, past);
            BlockState first = sample(index, source, pos, level.getSeed(),
                    MutationEventHandler.displayPeriodIndex(level));
            data.setClock(0.0, past);
            BlockState again = sample(index, source, pos, level.getSeed(),
                    MutationEventHandler.displayPeriodIndex(level));
            out.add("[period] revisiting period " + past + " replays the same state: "
                    + (again == first ? "PASS" : "FAIL " + first + " -> " + again));

            Set<Block> distinct = new HashSet<>();
            for (int back = 0; back < 16; back++) {
                distinct.add(sample(index, source, pos, level.getSeed(), livePeriod - back).getBlock());
            }
            out.add("[period] rolling back 16 periods reaches " + distinct.size()
                    + " distinct states: " + (distinct.size() >= 4 ? "PASS" : "FAIL"));
        } finally {
            data.setClock(savedSpeed, savedOffset);
        }
        out.add("[period] clock restored to speed=" + data.getClockSpeed() + " offset=" + data.getClockOffset());
        return out;
    }

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
