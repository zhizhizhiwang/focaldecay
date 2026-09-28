package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.InteractionHandler;
import com.zhizhiwang.focal_decay.mutation.ModelTrainingHandler;
import com.zhizhiwang.focal_decay.mutation.ThroneRitualHandler;
import com.zhizhiwang.focal_decay.mutation.MutationEventHandler;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationPoolManager;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
import com.zhizhiwang.focal_decay.mutation.MutationStateMapper;
import com.zhizhiwang.focal_decay.network.SyncClientViewPacket;
import com.zhizhiwang.focal_decay.network.SyncMutationSettingsPacket;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        lines.addAll(itemPoolAudit(index));
        lines.add("[mutation] checks: frozen=" + frozen
                + " asymmetric=" + asymmetric + " crossClass=" + crossClass
                + (frozen == 0 && asymmetric == 0 && crossClass == 0 ? "  OK" : "  FAIL"));
        lines.addAll(problems);
        return lines;
    }

    /**
     * 掉落物池审计（2026-09-25）。
     * <p>
     * 钉的是"丢件"：掉落物突变只能从<b>确实注册了物品</b>的方块里抽。没有 {@code BlockItem} 的方块
     * 其 {@code asItem()} 是 {@link net.minecraft.world.item.Items#AIR}，用它构造 {@code ItemStack}
     * 会得到空栈，而空栈物品实体下一 tick 就被 {@code ItemEntity#tick} 丢弃——物品凭空消失且无日志。
     * <p>
     * 断言两条：物品池非空（否则掉落物突变整个失效，是另一种坏法）；
     * 以及物品池里每一个物品都不是 AIR。后者在 {@code MutationIndex#itemPool()} 的构建期过滤下
     * 恒成立，所以它的作用是<b>回归网</b>：有人把过滤去掉、或改用方块池时立刻 FAIL。
     */
    private static List<String> itemPoolAudit(MutationIndex index) {
        List<String> lines = new ArrayList<>();
        Item[] items = index.itemPool();
        int air = 0;
        for (Item item : items) {
            if (item == Items.AIR) {
                air++;
            }
        }
        int blocksWithoutItem = index.blocksWithoutItem();
        boolean ok = items.length > 0 && air == 0;
        lines.add("[mutation] item pool (drop mutation targets): " + items.length + " items from "
                + index.wild().flat().length + " wild blocks"
                + " (+" + blocksWithoutItem + " blocks have no item and are excluded)"
                + (ok ? "  OK" : "  FAIL"));
        // 把"是哪些方块"点出来：这正是丢件的来源，值得在日志里留名（数据包加料时会变多）。
        if (blocksWithoutItem > 0) {
            List<String> noItem = new ArrayList<>();
            for (Block block : index.wild().flat()) {
                if (block.asItem() == Items.AIR && noItem.size() < 16) {
                    noItem.add(id(block));
                }
            }
            lines.add("[mutation]   blocks with no item (cannot be drop targets): " + noItem);
        }
        if (air > 0) {
            lines.add("[mutation]   FAIL: " + air + " pool entries have no item (asItem()==AIR)"
                    + " - those would silently delete items on mutation");
        }
        if (items.length == 0) {
            lines.add("[mutation]   FAIL: item pool is empty - drop mutation would never fire");
        }
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
        // tier 护栏的代价：<b>同一进程里</b>前后各测一次。拿"历史上某次运行的数字"当基线会把
        // 机器负载与 JIT 状态的变化一起算到护栏头上，而这条 A/B 把两者分开；
        // 跨运行的 A/B 仍然可用（{@code -Dfocaldecay.abNoTier=true} 让 gateDisabled 一开始就是 true）。
        Tiers.setGateDisabledForTest(true);
        long gateOffFast;
        long gateOffSlow;
        try {
            gateOffFast = bench(index, source, pos, seed, 1.0);
            gateOffSlow = bench(index, source, pos, seed, 0.01);
        } finally {
            Tiers.setGateDisabledForTest(false);
        }
        out.add("[selftest]   same with the tier gate OFF (in-process A/B): chance=1.00 "
                + gateOffFast + " ns, chance=0.01 " + gateOffSlow + " ns");
        out.addAll(mapperStressTest(index));
        out.addAll(itemMutationSelfTest(index));
        out.addAll(modelDataInvariantSelfTest());
        out.addAll(entityProtectionSelfTest(index));
        out.addAll(syncSelfTest(level, pos));
        out.addAll(tierSelfTest(index));
        return out;
    }

    /**
     * 实体保护判定的自测（2026-09-25，BACKLOG `P1-3`）。
     * <p>
     * 规则原本散在 {@code DoomsdayHandler#isEntityProtected} 的循环里，那里对每个实体都要
     * 造一个实体类型 ID 字符串、再对每个效果做 {@code List<String>.contains}；
     * 现在收进 {@code PrototypeEffect#protectsEntity(EntityType, boolean)} 这个<b>纯函数</b>
     * ——顺便也就变得可以直接测了。
     * <p>
     * 断言逐型号走一遍，包括两个容易写反的边界：
     * 生物稳定模型的"能量为 0 即失效"、语义锁定"只护住被训练的<b>那几种</b>实体"。
     */
    private static List<String> entityProtectionSelfTest(MutationIndex index) {
        List<String> out = new ArrayList<>();
        BlockPos pos = BlockPos.ZERO;
        Set<Block> noBlocks = Set.of();

        ObserverModelData total = new ObserverModelData(ObserverModelData.TYPE_TOTAL, List.of(), List.of(),
                1.0, "", 0, 0, true, 0);
        ObserverModelData bioCharged = new ObserverModelData(ObserverModelData.TYPE_BIO, List.of(), List.of(),
                1.0, "", 0, 100, false, 0);
        ObserverModelData bioDrained = new ObserverModelData(ObserverModelData.TYPE_BIO, List.of(), List.of(),
                1.0, "", 0, 0, false, 0);
        ObserverModelData lockPig = new ObserverModelData(ObserverModelData.TYPE_SEMANTIC_LOCK,
                List.of(), List.of("minecraft:pig"), 1.0, "", 0, 0, false, 0);
        ObserverModelData guided = new ObserverModelData(ObserverModelData.TYPE_GUIDED, List.of(), List.of(),
                0.5, "focal_decay:concept/stone", 0, 0, false, 0);

        boolean ok = true;
        ok &= effect(total, noBlocks, Set.of()).protectsEntity(EntityType.PIG, true);
        // 生物稳定：能量 > 0 且开关打开才保护；开关关掉就不保护（这是服务端语义配置）
        ok &= effect(bioCharged, noBlocks, Set.of()).protectsEntity(EntityType.PIG, true);
        ok &= !effect(bioCharged, noBlocks, Set.of()).protectsEntity(EntityType.PIG, false);
        ok &= !effect(bioDrained, noBlocks, Set.of()).protectsEntity(EntityType.PIG, true);
        // 语义锁定：只护住训练过的那一种
        ok &= effect(lockPig, noBlocks, Set.of(EntityType.PIG)).protectsEntity(EntityType.PIG, true);
        ok &= !effect(lockPig, noBlocks, Set.of(EntityType.PIG)).protectsEntity(EntityType.COW, true);
        // 引导模型不是保护型：它只扰动漂移方向
        ok &= !effect(guided, noBlocks, Set.of()).protectsEntity(EntityType.PIG, true);

        out.add("[selftest] entity protection rules per model type"
                + " (total / bio on+off / bio drained / lock trained+untrained / guided): "
                + (ok ? "PASS" : "FAIL"));
        out.addAll(entityProtectionSpatialSelfTest());
        return out;
    }

    /** 半径判定也是纯函数了，顺手验一下边界（半径是切比雪夫距离）。 */
    private static List<String> entityProtectionSpatialSelfTest() {
        List<String> out = new ArrayList<>();
        MutationPoolManager.PrototypeEffect effect = new MutationPoolManager.PrototypeEffect(
                new BlockPos(10, 64, 10), 4,
                new ObserverModelData(ObserverModelData.TYPE_TOTAL, List.of(), List.of(),
                        1.0, "", 0, 0, true, 0),
                Set.of(), Set.of(), null, net.minecraft.world.level.Level.OVERWORLD);
        boolean ok = effect.withinRadius(new BlockPos(14, 64, 10))
                && effect.withinRadius(new BlockPos(10, 68, 6))     // 三个轴都吃半径
                && !effect.withinRadius(new BlockPos(15, 64, 10))   // 恰好出界
                && !effect.withinRadius(new BlockPos(10, 69, 10));
        out.add("[selftest] prototype radius uses Chebyshev distance (inclusive boundary): "
                + (ok ? "PASS" : "FAIL"));
        return out;
    }

    private static MutationPoolManager.PrototypeEffect effect(ObserverModelData data,
                                                              Set<Block> trained,
                                                              Set<EntityType<?>> trainedEntities) {
        return new MutationPoolManager.PrototypeEffect(BlockPos.ZERO, 8, data, trained, trainedEntities, null,
                net.minecraft.world.level.Level.OVERWORLD);
    }

    /**
     * {@link ObserverModelData} 的入参不变式自测（2026-09-25，BACKLOG `P1-6` 第 8 条）。
     * <p>
     * 这些字段都可以从 NBT / 组件 / 命令直接读入，而 `copies` 会进入
     * {@code MutationPoolManager#totalStabilityRadius} 的三角数计算——
     * 越界值不该让半径算到溢出上（半径会被当作固化循环的边界）。
     * 断言用"故意喂越界值，看规范化有没有把它们拉回合法域"来验，而不是只验 happy path。
     */
    private static List<String> modelDataInvariantSelfTest() {
        List<String> out = new ArrayList<>();
        ObserverModelData wild = new ObserverModelData(
                ObserverModelData.TYPE_TOTAL, List.of(), List.of(),
                5.0, null, -7, -3, false, Integer.MAX_VALUE);
        boolean copiesClamped = wild.copies() == ObserverModelData.MAX_COPIES;
        boolean progressClamped = wild.progress() == 0;
        boolean energyClamped = wild.bioEnergy() == 0;
        boolean strengthClamped = wild.stabilityStrength() == 1.0;
        boolean conceptNotNull = wild.concept().isEmpty();
        boolean ok = copiesClamped && progressClamped && energyClamped && strengthClamped && conceptNotNull;
        out.add("[selftest] model data clamps out-of-range inputs (copies/progress/energy/strength/concept): "
                + (ok ? "PASS" : "FAIL copies=" + wild.copies() + " progress=" + wild.progress()
                + " energy=" + wild.bioEnergy() + " strength=" + wild.stabilityStrength()
                + " concept=" + wild.concept()));
        // 半径不能因为越界 copies 而变成负数或巨大值（它是固化循环的边界）。
        int radius = MutationPoolManager.radiusFor(wild);
        boolean radiusSane = radius >= FocalDecayConfig.PROTOTYPE_RADIUS.get() && radius <= 32;
        out.add("[selftest] total stability radius stays within [base, 32] for extreme copies: "
                + (radiusSane ? "PASS" : "FAIL -> " + radius));
        return out;
    }

    /**
     * 把一份快照编码再解码一遍。
     * <p>
     * 用于"某个字段到底有没有被搬"这类断言：手写编解码漏搬字段时，两端的值都会落到该字段的默认值上，
     * 逐字段 {@code equals} 是看不出来的（两边一致地错）。把某个字段翻转后再走一遍，
     * 如果解码结果没跟着变，就说明它根本没上线。
     */
    private static MutationSettings decode(MutationSettings settings) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncMutationSettingsPacket.STREAM_CODEC.encode(buf, new SyncMutationSettingsPacket(settings));
        return SyncMutationSettingsPacket.STREAM_CODEC.decode(buf).settings();
    }

    /**
     * 掉落物突变的端到端自测（2026-09-25）。
     * <p>
     * 走的是 {@link com.zhizhiwang.focal_decay.mutation.DoomsdayHandler} 实际使用的那条路径：
     * 从 {@link MutationIndex#itemPool()} 里按索引取物品，再对原栈做
     * {@code ItemStack#transmuteCopy}（换物品类型、保留组件与数量）。
     * 断言两件事：
     * <ol>
     *   <li><b>结果永不空栈</b>——空栈物品实体会在下一 tick 被丢弃，也就是物品凭空消失。
     *       这是本次修复的核心性质，且它与"池里有没有无物品方块"无关，因此不是空断言。</li>
     *   <li><b>组件的去留</b>——顺带钉住"换物品不丢组件"（原来 {@code new ItemStack(item, count)}
     *       会把附魔/命名/容器内容全部丢掉）。用自定义名称当探针，它对任何物品都合法。</li>
     * </ol>
     */
    private static List<String> itemMutationSelfTest(MutationIndex index) {
        List<String> out = new ArrayList<>();
        Item[] targets = index.itemPool();
        if (targets.length == 0) {
            out.add("[selftest] item mutation targets: FAIL (empty pool - drop mutation would never fire)");
            return out;
        }

        // 用固定种子遍历（而不是随机抽样）：失败时可复现，也让两端的自测输出一致。
        RandomSource probe = RandomSource.create(0x5EED_1703L);
        int empty = 0;
        int componentLost = 0;
        int samples = Math.min(256, targets.length);
        for (int i = 0; i < samples; i++) {
            Item target = targets[probe.nextInt(targets.length)];
            ItemStack origin = new ItemStack(Items.STONE, 7);
            origin.set(DataComponents.CUSTOM_NAME, Component.literal("probe"));
            ItemStack mutated = origin.transmuteCopy(target, origin.getCount());
            if (mutated.isEmpty()) {
                empty++;
            }
            if (!mutated.has(DataComponents.CUSTOM_NAME)) {
                componentLost++;
            }
        }
        // 回归网必须是<b>确定性全量</b>的：抽查 256 个目标时，池里混进的那一个无物品方块
        // 很可能抽不到，于是"改坏了也 PASS"（这条 A/B 时踩到过）。所以这里逐项扫一遍。
        int airInPool = 0;
        for (Item item : targets) {
            if (item == Items.AIR) {
                airInPool++;
            }
        }
        out.add("[selftest] item pool has no itemless entry (full scan of " + targets.length + "): "
                + (airInPool == 0 ? "PASS" : "FAIL (" + airInPool + " AIR entries - drops would vanish)"));
        out.add("[selftest] item mutation keeps the stack non-empty over " + samples + " targets: "
                + (empty == 0 ? "PASS" : "FAIL (" + empty + " empty results - items would vanish)"));
        out.add("[selftest] item mutation keeps components (custom name probe): "
                + (componentLost == 0 ? "PASS" : "FAIL (" + componentLost + " lost)"));
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
        MutationSettings decoded = decode(settings);
        out.add("[sync] settings packet round-trip (13 fields): "
                + (decoded.equals(settings) ? "PASS" : "FAIL\n  sent=" + settings + "\n  read=" + decoded));

        // 逐字段比对有个盲区：手写编解码<b>漏搬一个字段</b>时，两边都会落到该字段的默认值上，
        // equals 依然成立 —— 静默分叉，正是手写编解码最危险的失败方式。
        // 下面这条用"翻转该字段后解码结果是否跟着变"来证明它确实被搬了：漏搬则解码回到默认值，
        // 与原值相同，于是这条 FAIL。
        // ⚠️ 判据不能是"线上长度变没变"——writeBoolean 无论真假都只写 1 字节，长度恒等。
        //    （第一版就是这么写的，它正确地 FAIL 了，等于这条断言先抓住了自己。）
        MutationSettings flipped = new MutationSettings(
                settings.worldSeed(), settings.baseInterval(), settings.stageSystem(),
                settings.stage2Day(), settings.stage3Day(), settings.chanceStage1(),
                settings.chanceStage2(), settings.chanceStage3(), settings.wildChance(),
                settings.semanticLockStage3(), settings.guidedStage3Halve(),
                !settings.wildAutoInclude(), settings.upTierChance());
        boolean movesWithField = !decode(flipped).equals(settings);
        out.add("[sync] settings packet actually carries wild_auto_include"
                + " (flipping it changes the decoded value): "
                + (movesWithField ? "PASS" : "FAIL (field is not encoded - both sides would silently default)"));
        out.add("[sync]   (wild_auto_include is " + settings.wildAutoInclude()
                + ", which decides wild pool membership and therefore every target)");

        // 同一个盲区对本轮新增的字段同样成立：漏搬时两端会各自退回默认值，
        // 而"跨级概率"两端不同 = 两端对同一格给出不同的目标。
        MutationSettings flippedTier = new MutationSettings(
                settings.worldSeed(), settings.baseInterval(), settings.stageSystem(),
                settings.stage2Day(), settings.stage3Day(), settings.chanceStage1(),
                settings.chanceStage2(), settings.chanceStage3(), settings.wildChance(),
                settings.semanticLockStage3(), settings.guidedStage3Halve(),
                settings.wildAutoInclude(), settings.upTierChance() + 0.25);
        boolean upTierMoves = !decode(flippedTier).equals(settings);
        out.add("[sync] settings packet actually carries guide_up_tier_chance"
                + " (flipping it changes the decoded value): "
                + (upTierMoves ? "PASS" : "FAIL (field is not encoded - both sides would silently default)"));
        out.add("[sync]   (guide_up_tier_chance is " + settings.upTierChance()
                + ", the only way a guided mutation may reach one tier above its source)");

        // 原型机摘要也走手写编解码（bioActive / candidateComplete 由 int 改成 boolean 时最容易串位）
        SyncRegionDataPacket.PrototypeData prototype = new SyncRegionDataPacket.PrototypeData(
                4321L, 8, ObserverModelData.TYPE_BIO, List.of("minecraft:stone", "minecraft:oak_log"),
                List.of("minecraft:pig"), true, "focal_decay:concept/stone", true, 0.75);
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

        // ---- 3. 阶段/时钟：快照里的换算必须与本端配置一致 ----
        boolean stageOk = true;
        boolean clockOk = true;
        for (long days = 0; days <= 10; days++) {
            stageOk &= settings.stage(days) == MutationHelper.currentStage(days);
        }
        for (long tick = 0; tick < 5000; tick += 137) {
            clockOk &= settings.displayPeriod(tick, 1.0, 0L) == MutationHelper.displayPeriod(tick, 1.0, 0L);
            clockOk &= settings.storagePeriod(tick) == MutationHelper.blockPeriod(tick);
        }
        out.add("[sync] stage / clock parity: "
                + ((stageOk && clockOk) ? "PASS" : "FAIL stage=" + stageOk + " clock=" + clockOk));

        // ---- 3b. 候选观测者（OBSR-3）的训练增益 ----
        // 2026-09-17 修的真实 bug：派生配方曾把 OBSR-EX 的复制代数整个丢掉，"副本合成的 -3 更难练"
        // 从未生效（练满永远是 100 点）。这里把"增益表 → 需求 → 显示百分比 → 练满判定"整条链钉住，
        // 断言全是结构性的（配置改了也成立），具体数字打进日志备查。
        List<? extends Number> gains = FocalDecayConfig.CANDIDATE_COPY_GAIN.get();
        int candidateBase = FocalDecayConfig.CANDIDATE_REQUIRED_POINTS.get();
        int need0 = ObserverModelData.requiredCandidatePoints(0, candidateBase, gains);
        int need1 = ObserverModelData.requiredCandidatePoints(1, candidateBase, gains);
        int need2 = ObserverModelData.requiredCandidatePoints(2, candidateBase, gains);
        int fragmentPoints = Math.max(0, FocalDecayConfig.CANDIDATE_FRAGMENT_POINTS.get());
        // 需求随代数单调不减；顶点恒为 100% 且不会超过；碎片注入的百分比随代数递减
        boolean candidateOk = need0 <= need1 && need1 <= need2
                && ObserverModelData.candidatePercent(need0, need0) == 100
                && ObserverModelData.candidatePercent(need0 - 1, need0) == 99
                && ObserverModelData.candidatePercent(need0 * 3, need0) == 100;
        String candidateDetail = "";
        if (need1 > need0) {
            int share0 = ObserverModelData.candidatePercent(fragmentPoints, need0);
            int share1 = ObserverModelData.candidatePercent(fragmentPoints, need1);
            int share2 = ObserverModelData.candidatePercent(fragmentPoints, need2);
            candidateOk &= share1 < share0 && share2 <= share1;
            candidateDetail = ", fragment " + share0 + "% / " + share1 + "% / " + share2 + "%";
        }
        // "练满 = 硬保护"用的就是同一份数据：差一点不算练满，够了才算
        candidateOk &= !candidate(need1 - 1, 1).candidateComplete() && candidate(need1, 1).candidateComplete();
        out.add("[sync] candidate training (required " + need0 + "/" + need1 + "/" + need2
                + ", 100% cap, copy penalty): " + (candidateOk ? "PASS" : "FAIL")
                + candidateDetail);

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

        // ---- 模型数据的两条界限（BACKLOG P1-6 第 13、14 条） ----
        out.addAll(modelBoundsSelfTest());

        // ---- 索引派生缓存能被释放（BACKLOG P1-6 第 11 条） ----
        out.add(tagPoolReleaseSelfTest(index));

        // ---- 仪式决策：离线不该销毁进度（实机 §3b.3 发现） ----
        out.add(ritualDecisionSelfTest());
        return out;
    }

    /**
     * 仪式该暂停还是失败（<b>纯函数</b>断言）。
     * <p>
     * 这条守的是 2026-09-26 实机发现的一个**数据销毁**级缺陷：玩家退出世界时
     * {@code level.getPlayerByUUID} 返回 null，而那条分支**无条件 {@code fail}**，
     * {@code fail} → {@code stop()} 会清空 {@code playerId} —— 于是"一退出世界，仪式进度就没了"。
     * 而且它<b>崩溃与正常退出表现相同</b>（走同一分支），所以现象看起来像"服务端崩溃导致回退"。
     * <p>
     * 为什么必须放在这里而不是靠实机：**离线路径在无头自测里根本走不到**
     * （没有真实玩家会登录又退出），所以这是唯一能拦住它回归的地方。
     * <p>
     * A/B：把 {@code decide} 的 {@code alive == null || !alive} 分支改回无条件
     * {@code FAIL}（即原实现），第 1、2 条会 FAIL。
     */
    private static String ritualDecisionSelfTest() {
        List<String> bad = new ArrayList<>();

        // 1. 离线 + pause_on_leave=true → 暂停（保留进度）。这是本次修的核心。
        if (ThroneRitualHandler.decide(null, true, true) != ThroneRitualHandler.RitualAction.PAUSE) {
            bad.add("offline with pause_on_leave=true must PAUSE (it used to FAIL and wipe playerId)");
        }
        // 2. 离线 + pause_on_leave=false → 失败（尊重配置）
        if (ThroneRitualHandler.decide(null, true, false) != ThroneRitualHandler.RitualAction.FAIL) {
            bad.add("offline with pause_on_leave=false must FAIL");
        }
        // 3. 在线且在半径内 → 继续
        if (ThroneRitualHandler.decide(true, true, true) != ThroneRitualHandler.RitualAction.CONTINUE) {
            bad.add("online and in radius must CONTINUE");
        }
        // 4. 在线但离开半径 → 受 pause_on_leave 控制（原行为，防回归）
        if (ThroneRitualHandler.decide(true, false, true) != ThroneRitualHandler.RitualAction.PAUSE) {
            bad.add("leaving the radius with pause_on_leave=true must PAUSE");
        }
        if (ThroneRitualHandler.decide(true, false, false) != ThroneRitualHandler.RitualAction.FAIL) {
            bad.add("leaving the radius with pause_on_leave=false must FAIL");
        }
        // 5. 死亡 → 失败
        if (ThroneRitualHandler.decide(false, true, true) != ThroneRitualHandler.RitualAction.FAIL) {
            bad.add("a dead player must FAIL");
        }
        // 6. **离线与死亡必须被区别对待**（离线 null → PAUSE，死亡 false → FAIL）。
        //    这一条不是多余的：第一版断言把两者混成一个参数，于是"离线应暂停"与"死亡应失败"
        //    直接矛盾、断言自己 FAIL。现在把"它们不能相同"本身钉下来，
        //    以后谁把两个分支合并回去，这里会立刻响。
        if (ThroneRitualHandler.decide(null, true, true) == ThroneRitualHandler.decide(false, true, true)) {
            bad.add("offline and dead must NOT be treated the same"
                    + " (logging off keeps progress; dying fails)");
        }
        return "[ritual] offline pauses instead of wiping progress; death still fails"
                + " (logoff must never destroy the ritual): "
                + (bad.isEmpty() ? "PASS" : "FAIL (" + String.join("; ", bad) + ")");
    }

    /**
     * {@code MutationIndex#releaseCaches()} 真的把动态标签池清掉了（BACKLOG P1-6 第 11 条）。
     * <p>
     * <b>为什么要专门测这个</b>：那个"泄漏"不表现为功能错误，而是"几百份
     * {@code boolean[registry.size()]} 多活一个 GC 周期"，只会在反复重载数据包时长胖。
     * 既然没有可观测的症状，就只能直接问一句"表空了没有"——所以这里用
     * {@link MutationIndex#cachedTagPoolCount()}。
     * <p>
     * <b>取样用真实标签列表里的第一个非空标签</b>，不写死名字：写死的话数据包换了标签名
     * 这条断言就会退化成空跑（池为空 → 前后都是 0 → 恒通过，而且看不出原因）。
     * <p>
     * <b>A/B</b>：把 {@code releaseCaches()} 改成空方法体，这条会 FAIL（表非空）。
     */
    private static String tagPoolReleaseSelfTest(MutationIndex index) {
        // getTagNames() 返回的是 Stream（不是 Iterable），所以这里显式取迭代器：
        // 用 stream().filter(...).findFirst() 也能写，但那样每次都要构造一遍流水线，
        // 而这里唯一的目的是"拿到第一个非空标签"，迭代器更直白。
        String probeTag = null;
        var tags = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getTagNames().iterator();
        while (tags.hasNext()) {
            var tag = tags.next();
            if (!index.tagged(tag.location().toString()).isEmpty()) {
                probeTag = tag.location().toString();
                break;
            }
        }
        if (probeTag == null) {
            return "[selftest] tag pool release: FAIL (no non-empty block tag to probe with"
                    + " - tags not loaded?)";
        }
        int before = index.cachedTagPoolCount();
        index.releaseCaches();
        int after = index.cachedTagPoolCount();
        // 释放后查询仍然必须给出正确内容（releaseCaches 只清表，不该弄坏任何东西）
        boolean stillCorrect = !index.tagged(probeTag).isEmpty();
        boolean ok = before > 0 && after == 0 && stillCorrect;
        return "[selftest] tag pool release empties the derived cache and keeps lookups working"
                + " (probe tag " + probeTag + "): " + (ok ? "PASS" : "FAIL")
                + " cached before=" + before + " after=" + after + " lookupOk=" + stillCorrect;
    }

    /**
     * 模型数据的两条界限（BACKLOG P1-6 第 13、14 条）。
     * <p>
     * 两条都是"改动很小、改错了只有真人玩到才发现"的类型，所以值得在这里钉住。
     */
    private static List<String> modelBoundsSelfTest() {
        List<String> out = new ArrayList<>();

        // ---- P1-6 第 14 条：副手训练的重同步槽位 ----
        // 旧实现写死 `inventory.selected`（主手槽），副手训练时客户端看到的是
        // "主手槽被写入了副手那枚模型的组件"。副手分支<b>不解引用 player</b>，
        // 所以这里能安全地传 null——而如果哪天有人把它改成也去读 player，
        // 这条断言会立刻以 NPE 暴露，而不是静默变成一条测不到东西的假断言。
        int offhandSlot = ModelTrainingHandler.syncSlotFor(null, InteractionHand.OFF_HAND);
        boolean offhandOk = offhandSlot == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;
        out.add("[model] offhand training resyncs the offhand slot, not the selected hotbar slot: "
                + (offhandOk ? "PASS" : "FAIL -> got slot " + offhandSlot + ", expected "
                + net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND));

        // ---- P1-6 第 13 条：候选体有独立且合理的有界上限 ----
        // 候选体豁免 training_max_targets 是有意的（练满需要超过 64 条记录），
        // 但"豁免"不等于"无界"：判定以前写成 `!candidate && size >= limit`，
        // 于是候选体只被 candidate_required_points 挡着，而那一项可以配得很大。
        int candidateCap = FocalDecayConfig.CANDIDATE_MAX_TARGETS.get();
        int trainingCap = FocalDecayConfig.TRAINING_MAX_TARGETS.get();
        boolean capSane = candidateCap >= trainingCap && candidateCap <= 8192;
        out.add("[model] candidate observers have their own bounded record cap"
                + " (candidate=" + candidateCap + ", training=" + trainingCap + "): "
                + (capSane ? "PASS" : "FAIL -> candidate cap must be >= training cap and <= 8192"));

        return out;
    }

    /** 自测用：造一个候选体数据（只关心进度与代数）。 */
    private static ObserverModelData candidate(int progress, int copies) {
        return new ObserverModelData(ObserverModelData.TYPE_CANDIDATE, List.of(), List.of(),
                0.0, "", progress, 0, false, copies);
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
        // 逐条记下失败原因：只留最后一条会把"倍率 7 时闸门根本没关"这种真正的病因盖掉
        List<String> failures = new ArrayList<>();
        // 纯诊断：这条断言跑在什么时钟状态下（display != storage 是非 1.0 倍率的正常状态）。
        List<String> clockNotes = new ArrayList<>();
        try {
            for (double speed : new double[]{1.0, 7.0, 0.5}) {
                // ⚠️ offset 必须一起设成已知值（2026-09-25 修）。
                // 只设 speed、把 offset 留给现场，这条断言就会<b>依赖开发世界当时的状态</b>。
                worldData.setClock(speed, 0L);
                long now = MutationEventHandler.displayPeriodIndex(level);
                long birth = MutationEventHandler.birthPeriodIndex(level, Long.MIN_VALUE);

                // 这一行的两个数字现在是纯诊断（下面三条断言都不再依赖它们）：
                // 它们回答的是"这条断言跑在什么时钟状态下"。display != storage 是<b>非 1.0 倍率</b>
                // 的正常状态，不是问题；speed=1 时两者必须逐位相同（那由 {@code [period]} 段守）。
                long storageNow = MutationEventHandler.storagePeriodIndex(level);
                clockNotes.add("speed=" + speed + " display=" + now + " storage=" + storageNow
                        + " birth=" + birth);

                // ---- 断言 A：新生的方块这一刻不该变 ----
                if (MutationHelper.resolve(source, pos, seed, now, index, 1.0, GuidedBias.NONE,
                        MutationHelper.Protection.NONE, birth) != source) {
                    frozenOk = false;
                    failures.add("speed=" + speed + ": newborn block mutated right away");
                }

                // ---- 断言 B：往后看几个周期必须能变（抽到自己也是合法结果，所以看 3 个） ----
                boolean mutates = false;
                for (long p = now; p <= now + 3; p++) {
                    mutates |= p > now && MutationHelper.resolve(source, pos, seed, p, index, 1.0,
                            GuidedBias.NONE, MutationHelper.Protection.NONE, birth) != source;
                }
                if (!mutates) {
                    mutatesOk = false;
                    failures.add("speed=" + speed + ": still frozen after three periods");
                }
            }

            // ⚠️ 下面这一段是**换掉一个错误的对照物**之后重写的（2026-09-25 第二次修）。
            //
            // 原版拿 {@code storagePeriodIndex} 当"错误的诞生周期"，断言它与真实诞生周期
            // 会给出不同判定。这个对照物是错的，理由在代码里才看得见：
            // 回扫从 {@code birth + 1} 开始、深度上限 {@code CUMULATIVE_SCAN_CAP = 128}，
            // 而存储刻算出来的周期（实测 952）比显示刻（实测 6664）小得多——
            // 于是<b>两个诞生周期都让回扫一路跑到 128 上限</b>，判定自然相同。
            // 换句话说：那条断言测的是"回扫上限有没有生效"，不是"诞生周期有没有用"。
            // 它在 speed=0.5 时碰巧通过（那时两个周期接近），在 speed=7 时必然失败。
            //
            // 现在改成三条独立断言，最后一条才是"哪个时钟在起作用"的正面检验。
            for (double speed : new double[]{1.0, 7.0, 0.5}) {
                worldData.setClock(speed, 0L);
                long now = MutationEventHandler.displayPeriodIndex(level);
                long birth = MutationEventHandler.birthPeriodIndex(level, Long.MIN_VALUE);

                // ---- 断言 C：诞生周期确实被封住（未来诞生 ⇒ 恒定返回原方块） ----
                boolean sealed = true;
                for (long p = now; p <= now + 3; p++) {
                    sealed &= MutationHelper.resolve(source, pos, seed, p, index, 1.0, GuidedBias.NONE,
                            MutationHelper.Protection.NONE, now + 500L) == source;
                }
                if (!sealed) {
                    failures.add("speed=" + speed + ": a block born far in the future still mutated"
                            + " - the birth gate is not sealing");
                }

                // ---- 断言 D：诞生周期确实在起作用，而且是在**可达的**窗口里 ----
                //
                // ⚠️ 断言 D 与 E 的第一版都写错了，而且错法与本条断言最初那个 bug **同源**：
                // 它们都拿"极远的过去"当对照物。而回扫有两重上限——
                // {@code span = periodIndex - fromPeriod + 1} 与 {@code CUMULATIVE_SCAN_CAP = 128}
                // 取 min——所以<b>凡是距今超过 128 个周期的诞生周期，彼此都不可区分</b>：
                // 回溯窗口一律落在 [now-127, now]。
                // 实测：开发世界里 now 已经跑到 7168，于是 birth=now-500 与 birth=now-6668
                // 给出完全相同的判定，断言 D 稳定 FAIL。
                //
                // 换成一个**可达的、而且是必然成立的**对照物。
                //
                // 第二版仍然 flaky（实测 1193 通过 / 1205 失败）：它断言
                // "birth=now 与 birth=now-5 必定给出不同判定"，而**那不必然**——
                // 累积转换允许两者相同：k=1 抽中而 k=2..5 都没抽中时，两个窗口给出同一个结果。
                // 声明一个偶然成立的东西，它就会 flaky。
                //
                // **声明的应该是"必然"的那一面**。回扫窗口是
                //   [max(fromPeriod, periodIndex - CUMULATIVE_SCAN_CAP + 1), periodIndex]
                // 其中 fromPeriod = birth + 1。于是有一条**可证明**的性质：
                //   **两个诞生周期只要都早于 periodIndex - CAP + 1，就落在同一个有效窗口里，
                //   判定必然相同。**
                // 结合"刚出生的方块在 now 冻结"（断言 A），就得到"诞生周期确实在移动窗口"
                // 这个结论——而且与随机种子无关、不 flaky。
                int cap = MutationHelper.CUMULATIVE_SCAN_CAP;
                for (long p = now; p <= now + 3; p++) {
                    BlockState cutA = MutationHelper.resolve(source, pos, seed, p, index, 1.0,
                            GuidedBias.NONE, MutationHelper.Protection.NONE, Math.max(0L, p - cap - 10));
                    BlockState cutB = MutationHelper.resolve(source, pos, seed, p, index, 1.0,
                            GuidedBias.NONE, MutationHelper.Protection.NONE, Math.max(0L, p - cap - 500));
                    if (cutA != cutB) {
                        failures.add("speed=" + speed + ": two birth periods older than the scan cap"
                                + " (p=" + p + ", cap=" + cap + ") gave different verdicts"
                                + " - the cap is not defining the effective window");
                        break;
                    }
                }
                // 反向：刚刚出生的方块在 now 这一刻必须冻结（与断言 A 同源，但这里针对
                // "birth 参数真的被读到了"这一点再钉一次——若实现忽略该参数，A 也会失败，
                // 两条一起才说明 birth 既被读、又被上限约束。）
                if (MutationHelper.resolve(source, pos, seed, now, index, 1.0,
                        GuidedBias.NONE, MutationHelper.Protection.NONE, now) != source) {
                    failures.add("speed=" + speed + ": a block born now mutated at now"
                            + " - the newborn gate is not sealing (birth period ignored?)");
                }

                // ---- 断言 E：诞生周期取的是**显示刻**，不是存储刻 ----
                // 这条测的是"哪根时钟"，所以对照物必须是另一根时钟本身，而不是一个更远的偏移量
                // （那样会落进上面那个"超过 128 周期就不可区分"的坑）。
                // 而且**必须挑一个窗口**：存储刻诞生周期与显示刻相差很大时，两者的回溯窗口
                // 都可能已经撞上 128 上限而不可区分——所以这里扫一段更长的周期，要求至少有一个
                // 周期上两者判定不同；全都相同才判 FAIL。
                // speed=1 时两根时钟逐位相同，这条天然无意义（由 {@code [period]} 段守那件事）。
                if (speed != 1.0) {
                    long storageBirth = MutationEventHandler.storagePeriodIndex(level);
                    boolean discriminating = false;
                    for (long p = now; p <= now + 64; p++) {
                        discriminating |= MutationHelper.resolve(source, pos, seed, p, index, 1.0,
                                GuidedBias.NONE, MutationHelper.Protection.NONE, birth)
                                != MutationHelper.resolve(source, pos, seed, p, index, 1.0,
                                GuidedBias.NONE, MutationHelper.Protection.NONE, storageBirth);
                    }
                    if (!discriminating) {
                        failures.add("speed=" + speed + ": display-clock birth and storage-clock birth"
                                + " give identical verdicts over 64 periods (display=" + now
                                + " storage=" + storageBirth + " birth=" + birth + ")");
                    }
                }
            }
        } finally {
            worldData.setClock(savedSpeed, savedOffset);
        }
        // 标题与失败原因一起写清楚"到底测了哪几条"：这条断言 2026-09-25 被重写过一次，
        // 原措辞里的 "storage clock would differ" 描述的是一个**错误**的判据（见方法内的长注释），
        // 留着旧措辞会让下一个人以为它还在测那件事。
        return "[sync] birth gate (newborn frozen / mutates after 3 periods / future birth seals"
                + " / birth period moves the window / display clock drives it): "
                + ((frozenOk && mutatesOk && failures.isEmpty()) ? "PASS" : "FAIL " + String.join("; ", failures))
                + " [" + String.join(" | ", clockNotes) + "]";
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
    /** tier 自测的采样周期数（每个源方块）。 */
    private static final int TIER_SAMPLE_PERIODS = 128;

    /**
     * tier 护栏自测（2026-09-28，{@code DESIGN.md} §13.9 / 评审文档 §3.1–§3.3）。
     * <p>
     * 三条断言，缺一条这个功能就不成立：
     * <ol>
     *   <li><b>tier 表的关键格符合设计表</b>——染色块是 T1、玻璃是 T0、铁块 = 铁矿 + 1、
     *       下界合金块是唯一的 T4。这张表<b>就是</b>设计本身，所以逐格钉住（改一格就红）；</li>
     *   <li><b>自然失焦只降不升</b>：跨多个源方块 × {@value #TIER_SAMPLE_PERIODS} 个周期，
     *       没有任何目标超过 {@code min(源 tier, 3)}；</li>
     *   <li><b>它的控制组</b>：把护栏关掉，同一批样本<b>必须</b>真的出现越级。
     *       没有这一条，第 2 条可能是<b>空断言</b>——"池里本来就没有越级目标"与"护栏生效"
     *       在日志上完全一样（都是 0 次越级）。这正是 {@code PITFALLS.md} §8
     *       "给断言留一条控制组"那条教训的又一次应用。</li>
     * </ol>
     */
    private static List<String> tierSelfTest(MutationIndex index) {
        List<String> out = new ArrayList<>();
        BlockPos pos = BlockPos.ZERO;
        long seed = 20260928L;

        Map<Block, Integer> want = new LinkedHashMap<>();
        want.put(Blocks.STONE, 0);              // 挖就有
        want.put(Blocks.GLASS, 0);              // 烧一下就有（不需要出门）
        want.put(Blocks.IRON_ORE, 1);           // 石镐门槛
        want.put(Blocks.COPPER_ORE, 1);
        want.put(Blocks.WHITE_CONCRETE, 1);     // 染色：要染料
        want.put(Blocks.TERRACOTTA, 1);         // 只在恶地生成
        want.put(Blocks.WHITE_WOOL, 1);         // 要养羊
        want.put(Blocks.OBSIDIAN, 1);           // 水 + 岩浆就能刷，按工具门槛虚高
        want.put(Blocks.CUT_COPPER, 1);         // 原版工具门槛，保留（它本来就是装饰料）
        want.put(Blocks.IRON_BLOCK, 2);         // 压缩 = 原矿 + 1
        want.put(Blocks.RAW_IRON_BLOCK, 2);
        want.put(Blocks.DIAMOND_ORE, 2);
        want.put(Blocks.DIAMOND_BLOCK, 3);
        want.put(Blocks.GOLD_BLOCK, 3);
        want.put(Blocks.BEACON, 3);             // 挖着容易、得到很难：原版没有标签能表达
        want.put(Blocks.ANCIENT_DEBRIS, 3);
        want.put(Blocks.NETHERITE_BLOCK, Tiers.MAX_TIER);

        String mismatch = null;
        for (Map.Entry<Block, Integer> entry : want.entrySet()) {
            int got = index.tier(entry.getKey());
            if (got != entry.getValue()) {
                mismatch = id(entry.getKey()) + " expected T" + entry.getValue() + " got T" + got;
                break;
            }
        }
        out.add("[tier] tier table matches the design (" + want.size() + " spot checks): "
                + (mismatch == null ? "PASS" : "FAIL -> " + mismatch));
        out.add("[tier]   histogram: " + index.tierHistogram());

        List<String> topTier = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (index.tier(block) == Tiers.MAX_TIER) {
                topTier.add(id(block));
            }
        }
        out.add("[tier] T" + Tiers.MAX_TIER + " members (compressed form of a T3 ore; rite-only): " + topTier);

        List<Block> sources = List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.OAK_LOG, Blocks.WHITE_CONCRETE,
                Blocks.TERRACOTTA, Blocks.OBSIDIAN, Blocks.IRON_ORE, Blocks.DIAMOND_ORE,
                Blocks.ANCIENT_DEBRIS, Blocks.BEACON, Blocks.GLASS);
        int samples = sources.size() * TIER_SAMPLE_PERIODS;
        int violations = countTierViolations(index, sources, pos, seed);
        out.add("[tier] no target exceeds min(source tier, " + Tiers.MAX_NATURAL_TIER + ") over "
                + samples + " samples: " + (violations == 0 ? "PASS" : "FAIL (" + violations + ")"));

        Tiers.setGateDisabledForTest(true);
        int control;
        try {
            control = countTierViolations(index, sources, pos, seed);
        } finally {
            Tiers.setGateDisabledForTest(false);
        }
        out.add("[tier] control (gate off, same " + samples + " samples): " + control + " violations -> "
                + (control > 0
                        ? "PASS (the gate is what makes the line above true)"
                        : "FAIL (vacuous: no cross-tier target is ever produced,"
                          + " so the assertion above proves nothing)"));

        // ---- 4. 唯一的例外条款：引导下可以跨一级，而且只跨一级 ----
        // 用"必经"的参数（每周期必中 + 跨级概率 1）把这条路径逼到必然发生，断言就不依赖概率。
        // 石头(T0) 引着 ore 概念走：跨级开着时必须出现 T1（煤），且永远不出现 T2 以上；
        // 关掉之后必须一个 T1 都出不来 —— 这两条合起来才证明例外真的接通了、而且接到了该接的地方。
        MutationSettings base = MutationSettings.fromConfig(seed);
        GuidedBias oreBias = new GuidedBias(
                index.tagged(ModTags.Blocks.mutationPool("ore").location().toString()), 1.0);
        int reached = 0;
        int overshoot = 0;
        int blocked = 0;
        for (long period = 0; period < TIER_SAMPLE_PERIODS; period++) {
            Block target = MutationHelper.resolve(Blocks.STONE.defaultBlockState(), pos,
                    upTierSettings(base, 1.0), 1, period, index, oreBias,
                    MutationHelper.Protection.NONE, -1L).getBlock();
            int tier = index.tier(target);
            if (tier == 1) {
                reached++;
            } else if (tier > 1) {
                overshoot++;
            }
        }
        for (long period = 0; period < TIER_SAMPLE_PERIODS; period++) {
            Block target = MutationHelper.resolve(Blocks.STONE.defaultBlockState(), pos,
                    upTierSettings(base, 0.0), 1, period, index, oreBias,
                    MutationHelper.Protection.NONE, -1L).getBlock();
            if (index.tier(target) >= 1) {
                blocked++;
            }
        }
        out.add("[tier] guided up-tier exception (forced on): reached T1 in " + reached
                + "/" + TIER_SAMPLE_PERIODS + ", overshot past T1 in " + overshoot + " -> "
                + (reached > 0 && overshoot == 0 ? "PASS" : "FAIL (must reach exactly one tier)"));
        out.add("[tier] same samples with the exception off: " + blocked
                + " targets at T1 or above -> "
                + (blocked == 0 ? "PASS" : "FAIL (the exception is not wired)"));

        return out;
    }

    /** 只改"跨级概率"与"每周期必中"，用于把例外条款逼到必然发生（{@code withChance} 不动跨级概率）。 */
    private static MutationSettings upTierSettings(MutationSettings base, double upTierChance) {
        return new MutationSettings(base.worldSeed(), base.baseInterval(), base.stageSystem(),
                base.stage2Day(), base.stage3Day(), 1.0, 1.0, 1.0, base.wildChance(),
                base.semanticLockStage3(), base.guidedStage3Halve(), base.wildAutoInclude(), upTierChance);
    }

    /** 数"目标比源更文明"的样本数。判据自己算（{@code min(源, 3)}），不依赖护栏是否开着。 */
    private static int countTierViolations(MutationIndex index, List<Block> sources, BlockPos pos, long seed) {
        int violations = 0;
        for (Block source : sources) {
            int sourceTier = Math.min(index.tier(source), Tiers.MAX_NATURAL_TIER);
            for (long period = 0; period < TIER_SAMPLE_PERIODS; period++) {
                BlockState target = sample(index, source, pos, seed, period);
                if (index.tier(target.getBlock()) > sourceTier) {
                    violations++;
                }
            }
        }
        return violations;
    }

    private static BlockState sample(MutationIndex index, Block source, BlockPos pos, long seed, long period) {
        return sample(index, source.defaultBlockState(), pos, seed, period);
    }

    /** 同上，但直接给状态（用于带属性的样本，例如含水楼梯）。 */
    private static BlockState sample(MutationIndex index, BlockState source, BlockPos pos, long seed, long period) {
        return MutationHelper.resolve(source, pos, seed, period, index, 1.0,
                GuidedBias.NONE, MutationHelper.Protection.NONE, -1L);
    }
}
