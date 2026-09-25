package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 锚固化保护语义的自测（{@code /focaldecay mutation selftest} 的 {@code [anchor]} 一行）。
 * <p>
 * 钉的是 2026-09-25 修的那个缺陷：{@code convertPrototypeRange} 曾经硬传
 * {@link MutationHelper.Protection#NONE}，把"当前位置是否落在<b>别的</b>原型机硬保护范围内"
 * 这件事整个忽略了。后果是把新基座放进一个已存在的稳定场内部时，服务端会把那个场里本应冻结的
 * 方块按当前失焦态重写，而客户端仍按硬保护不显示幽灵——一个玩家能看见的失配。
 * <p>
 * <b>为什么用生物稳定模型而不是完全稳定模型</b>：两者走的是同一条硬保护分支
 * （{@code TYPE_BIO && bioEnergy > 0} 与 {@code TYPE_TOTAL} 都直接返回 HARD），但完全稳定模型半径 32，
 * 固化一次要遍历 65³ ≈ 27 万个坐标并把测试世界改得面目全非。生物稳定模型半径是"基础半径 + 4"，
 * 默认配置下是 12，代价小两个数量级，验的是同一段代码。
 * <p>
 * <b>探针是自己放的石头，不是现场原有方块</b>（第一版踩过）：现场位置很可能是空气或未加载的区块，
 * 于是固化循环连"这是不是一个突变源"都判不过去、一个坐标都不解析——把缺陷放回去测试照样 PASS，
 * 是一条空断言。所以这里明确放一块 {@code minecraft:stone}（它属于 {@code mutation_pool/stone}）
 * 来保证重叠区里<b>确实有</b>突变源。
 * <p>
 * <b>断言只看计数，不看任何方块的状态</b>（第二版踩过）：内层固化发生在保护登记之前，会把这块石头
 * 按当前失焦态改写掉（实测变成 {@code pink_wool}），所以"石头跑完还是石头"这种断言会无缘无故失败，
 * 而且它测的也不是本次修复。被测的性质是"外层固化有没有把既有保护区当成可写区域"，
 * 由 {@link MutationEventHandler.NormalizeStats} 的两个计数确定性回答：
 * <ul>
 *   <li>{@code scanned == 0}——没有任何坐标绕过保护进入解析；</li>
 *   <li>{@code skippedProtected > 0}——确实有突变源存在，并且是被保护拦下的（这一条排除空跑）。</li>
 * </ul>
 * 另有前置断言：石头确实是突变源、内层 bio 效果确实带着能量登记成功、两座锚确实建出来了——
 * 任何一道不过都直接 FAIL 而不是静默 PASS。
 * <p>
 * 跑完还原：拆掉临时基座、恢复方块与调试时钟。
 */
public final class AnchorNormalizeAudit {

    /** 内层锚的半径由 base radius + 4 决定（生物稳定模型的加成，见 MutationPoolManager#radiusFor）。 */
    private static final int INNER_RADIUS = FocalDecayConfig.PROTOTYPE_RADIUS.getDefault() + 4;
    /** 外层锚故意取得比内层小得多，且放在同一个中心：它的整个固化范围都落进内层保护区。 */
    private static final int OUTER_RADIUS = 5;
    /**
     * 探针离中心的距离。<b>必须同时满足两件事</b>：
     * ① {@code <= OUTER_RADIUS}——否则它根本不在外层锚的固化范围内，断言会变成空转；
     * ② {@code <= INNER_RADIUS}——这样它才处于内层硬保护之内，是"本应被守住"的那个位置。
     */
    private static final int PROBE_DISTANCE = 4;
    /** 给 bio 模型预充的能量：只要 > 0，保护就是硬保护（不依赖身边有没有生物）。 */
    private static final int BIO_ENERGY_FOR_TEST = 1000;

    private AnchorNormalizeAudit() {
    }

    public static List<String> selfTest(ServerLevel level, BlockPos probePos) {
        List<String> out = new ArrayList<>();
        BlockPos inner = probePos;

        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        if (worldData.isObserverOnline()) {
            out.add("[anchor] existing-protection check: SKIP (observer online - normalize is disabled)");
            return out;
        }
        if (!FocalDecayConfig.ANCHOR_NORMALIZE_RANGE.get()) {
            out.add("[anchor] existing-protection check: SKIP (anchor_normalize_range=false)");
            return out;
        }

        double savedSpeed = worldData.getClockSpeed();
        long savedOffset = worldData.getClockOffset();
        // 外层锚与内层同心，向上偏 4 格：既落在内层保护区内，又不覆盖内层锚方块本身
        // （固化的循环体会跳过锚自己那一格，所以叠在一起也无妨，但错开更好读）。
        BlockPos outer = inner.offset(0, 4, 0);
        BlockPos probe = inner.offset(PROBE_DISTANCE, 0, 0);
        BlockState[] saved = new BlockState[]{
                level.getBlockState(inner),
                level.getBlockState(outer),
                level.getBlockState(probe),
        };

        try {
            // 先调时钟再取周期：固化用的是 displayPeriod，概率必须真的大于 0，否则测试是空转。
            worldData.setClock(4.0, 0L);

            // 探针必须是一个<b>确定的突变源</b>。第一版直接用现场原有方块，结果整片区域都不是源
            // （空气或未加载），固化循环一个坐标都没走到解析——把缺陷放回去测试照样 PASS，
            // 是一条空断言（A/B 抓出来的）。所以这里自己放一块石头（它属于 mutation_pool/stone）。
            //
            // 注意<b>不要</b>断言"这块石头跑完还是石头"：内层固化（半径 12）发生在保护登记之前，
            // 会把它按当前失焦态改写掉（实测变成 pink_wool，而它同样是突变源）。
            // 被测的性质不是"某个方块没变"，而是"外层固化有没有把既有保护区当成可写区域"——
            // 那由下面两个计数确定性回答，不需要依赖任何方块的状态。
            level.setBlockAndUpdate(probe, Blocks.STONE.defaultBlockState());
            MutationPoolManager manager = MutationPoolManager.get(level);

            MutationIndex index = MutationIndexes.get(level.dimension());
            boolean probeIsSource = index.isSource(Blocks.STONE);
            if (!probeIsSource) {
                out.add("[anchor] existing-protection check  FAIL (stone is not a mutation source -"
                        + " the probe could never be rewritten, so the test would be vacuous)");
                return out;
            }

            if (!placeAnchor(level, inner)) {
                out.add("[anchor] existing-protection check  FAIL (inner anchor not created)");
                return out;
            }
            AnchorPrototypeBlockEntity innerBe = (AnchorPrototypeBlockEntity) level.getBlockEntity(inner);
            innerBe.setItem(0, energizedBioModel());
            innerBe.setChanged();

            // 内层此刻应登记为半径 12 的硬保护（bio + 能量 > 0）。这一条不过，后面的断言没有意义。
            boolean innerRegistered = manager.getPrototypeEffects().stream()
                    .anyMatch(e -> e.center().equals(inner.immutable())
                            && ObserverModelData.TYPE_BIO.equals(e.data().type())
                            && e.data().bioEnergy() > 0);
            if (!innerRegistered) {
                out.add("[anchor] existing-protection check  FAIL (inner bio effect not registered"
                        + " - cannot test what we cannot set up)");
                return out;
            }

            // 在保护区内部再放一座基座并固化。旧实现在这里传 Protection.NONE，
            // 于是外层锚(半径 5)会把范围内的源方块（探针所在位置）按当前失焦态重写，
            // 而它们本应被内层硬保护守住。
            if (!placeAnchor(level, outer)) {
                out.add("[anchor] existing-protection check  FAIL (outer anchor not created)");
                return out;
            }
            MutationEventHandler.NormalizeStats stats =
                    MutationEventHandler.convertPrototypeRange(level, outer, manager, OUTER_RADIUS);

            // 主断言（确定性，不依赖概率骰子，也不依赖任何方块的状态）：
            // 外层固化的整个作用域都在内层硬保护之内，所以
            //   scanned 必须为 0 —— 没有任何坐标绕过保护进入解析；
            //   skippedProtected 必须 > 0 —— 确实有突变源存在、并且是被保护拦下的。
            // 第二条同时排除"空跑"：若范围内根本没有源方块，两个计数都会是 0。
            // 旧实现传 Protection.NONE 时这两个数字恰好反转（实测 scanned=1 skipped=0）。
            boolean nothingScanned = stats.scanned() == 0;
            boolean somethingSkipped = stats.skippedProtected() > 0;
            boolean ok = nothingScanned && somethingSkipped;
            out.add("[anchor] existing hard protection is honored by a second anchor's normalize"
                    + " (inner r=" + INNER_RADIUS + ", outer r=" + OUTER_RADIUS
                    + ", probe d=" + PROBE_DISTANCE + "): "
                    + (ok ? "PASS" : "FAIL")
                    + " scanned=" + stats.scanned()
                    + " changed=" + stats.changed()
                    + " skippedProtected=" + stats.skippedProtected());
        } finally {
            // 还原：先拆两座临时基座，再放回原来的方块与调试时钟。
            restore(level, outer, saved[1]);
            restore(level, inner, saved[0]);
            level.setBlockAndUpdate(probe, saved[2]);
            worldData.setClock(savedSpeed, savedOffset);
        }
        return out;
    }

    private static boolean placeAnchor(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, ModBlocks.ANCHOR_PROTOTYPE.get().defaultBlockState());
        return level.getBlockEntity(pos) instanceof AnchorPrototypeBlockEntity;
    }

    private static void restore(ServerLevel level, BlockPos pos, BlockState original) {
        if (level.getBlockState(pos).is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            level.removeBlock(pos, false); // 连带移除方块实体与里面的模型
        }
        if (original != null) {
            level.setBlockAndUpdate(pos, original);
        }
    }

    /**
     * 预先充能的生物稳定模型。{@code ObserverModelData.bio()} 的能量是 0，
     * 而 0 能量的生物稳定模型<b>不是</b>硬保护（见 {@code MutationPoolManager#protectionInfo}），
     * 所以必须自己写一份带能量的，否则测试会因为"保护压根没生效"而失败在与被测缺陷无关的地方。
     */
    private static ItemStack energizedBioModel() {
        ObserverModelData data = new ObserverModelData(
                ObserverModelData.TYPE_BIO, List.of(), List.of(), 1.0, "", 0,
                BIO_ENERGY_FOR_TEST, false, 0);
        return ObserverModelItem.setData(new ItemStack(ModItems.BIO_STABILIZER_MODEL.get()), data);
    }
}
