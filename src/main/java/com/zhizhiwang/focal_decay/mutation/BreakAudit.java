package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.attachment.BreakData;
import com.zhizhiwang.focal_decay.attachment.ModAttachments;
import com.zhizhiwang.focal_decay.block.BreakProbeBlock;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 挖掘路径重构（BACKLOG P0-4）的自测，输出 {@code [break]} 段。
 * <p>
 * <b>被测的性质只有一条</b>：破坏时跑的是<b>原版管线</b>，而它面对的是<b>可见目标方块</b>。
 * 旧实现取消 {@code BreakEvent} 之后手工复刻了原版收尾，漏掉八项原版行为
 * （{@code playerWillDestroy} / {@code onDestroyedByPlayer} / {@code destroy} /
 * {@code playerDestroy} / {@code spawnAfterBreak} / {@code BlockDropsEvent} /
 * {@code RULE_DOBLOCKDROPS} / {@code onPlayerDestroyItem}）。
 * 那八项里能在这个自测里确定性验证的，本类逐条验；验不了的（例如"别的模组的
 * {@code BlockDropsEvent} 收到了"）留给实机矩阵 {@code P0-8}，在"验证"一节里写明。
 * <p>
 * <b>为什么用探针方块而不是现成方块</b>：要观测的是"原版调用了哪几个回调"，
 * 而这件事在现成方块上只能通过掉落间接推断，判据脆且受随机池影响。
 * 探针把"被调用了"变成整数计数，断言就成了确定性的整数比较。
 * 定义见 {@link BreakProbeBlock}。
 * <p>
 * <b>为什么用 FakePlayer</b>：必须跑真正的 {@code ServerPlayerGameMode#destroyBlock}
 * ——那正是重构要复用的那段代码。用假的事件对象或自己重写一遍管线，
 * 测的就是"我以为原版会怎么做"，而不是原版本身。自测跑在命令线程上，
 * 与真实玩家破坏不在同一刻，所以不会互相干扰（自测也不在专用服务器上并发跑）。
 * <p>
 * <b>跑完还原</b>：两个探针位置的方块、{@code doTileDrops} 规则、以及掉落出来的钻石。
 */
public final class BreakAudit {

    /** 真实方块（旧世界里的源）与目标各自占一格，横向隔开 3 格避免掉落物互相干扰。 */
    private static final int TARGET_OFFSET = 3;

    /**
     * 清理掉落物时，以探针位置为中心的半个边长。
     * <p>
     * 取值必须小于 {@code TARGET_OFFSET / 2}：两格相距 3，半径 1.5 的两个清理区恰好不重叠。
     * 这一点是踩出来的——第一版用 3.0，于是"清理 realPos"的盒子把 {@code targetPos} 那格
     * 也罩住了，上一条断言留下的钻石被清掉、下一条又数到不该数的，实测 {@code diamonds=2}。
     */
    private static final double DROP_CLEAR_RADIUS = 1.5;

    /**
     * 数掉落物时的半个边长，比清理半径大：掉落物有随机初速（±0.25 格）且会下落。
     * <p>
     * <b>但判据不靠这个盒子</b>：断言用的是"破坏前 / 破坏后的集合差集"
     * （见 {@link #newDrops}），所以盒子只要不把<b>另一个探针的掉落</b>框进来就行。
     */
    private static final double DROP_SEARCH_RADIUS = 2.5;

    /** 断言判据的盒：以 realPos 为西边界、覆盖 targetPos，两格的掉落都在里面。 */
    private static AABB dropArea(BlockPos realPos, BlockPos targetPos) {
        return new AABB(realPos).minmax(new AABB(targetPos)).inflate(DROP_SEARCH_RADIUS);
    }

    private BreakAudit() {
    }

    public static List<String> selfTest(ServerLevel level, BlockPos probePos) {
        List<String> out = new ArrayList<>();

        if (FocalDecayWorldData.get(level.getServer()).isObserverOnline()) {
            out.add("[break] vanilla-pipeline check: SKIP (observer online - conversions are disabled)");
            return out;
        }

        BlockPos realPos = probePos.immutable();
        BlockPos targetPos = realPos.offset(TARGET_OFFSET, 0, 0);
        BlockState probeSource = ModBlocks.BREAK_PROBE_SOURCE.get().defaultBlockState();
        BlockState probeTarget = ModBlocks.BREAK_PROBE_TARGET.get().defaultBlockState();

        BlockState[] saved = {level.getBlockState(realPos), level.getBlockState(targetPos)};
        GameRules.BooleanValue doTileDrops = level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS);
        boolean savedDoTileDrops = doTileDrops.get();

        // 假玩家只建一次：建一次要跑一遍 ServerPlayer 构造（含进度加载），没必要每个断言重建。
        ServerPlayer fake = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("focaldecay-break-audit".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "focaldecay_break_audit"));

        try {
            // 先跑不碰世界的纯函数断言：它们不需要任何现场布置，失败也不影响后面的现场。
            checkLockValidation(out, level, realPos, probeSource, probeTarget);

            // 每条断言都自己保证现场干净（上一条可能留下了空气或钻石）。
            // 清理必须放在**每条**断言的开头，而不是"跑一次然后指望上一条收尾"——
            // 断言之间是有副作用的：控制组会破坏目标格，于是它后面那条断言的
            // "目标探针应该还在原处"就不成立。第一版只在开头清了一次，
            // A/B 阶段以 "the untouched target probe changed, now minecraft:air" 暴露出来，
            // 而且它在正常路径下是**碰巧通过**的（被测代码恰好把现场恢复成了期望的样子），
            // 典型的环境依赖型假通过。
            resetSite(level, realPos, targetPos, probeSource, probeTarget);
            checkVanillaPipelineRunsOnTarget(out, level, fake, realPos, targetPos, probeSource, probeTarget);
            resetSite(level, realPos, targetPos, probeSource, probeTarget);
            checkCounterObservationIsNotVacuous(out, level, fake, realPos, targetPos, probeSource, probeTarget);
            resetSite(level, realPos, targetPos, probeSource, probeTarget);
            checkDoTileDropsGatesOutput(out, level, fake, realPos, targetPos, probeSource, probeTarget, doTileDrops);
        } finally {
            doTileDrops.set(savedDoTileDrops, level.getServer());
            restore(level, realPos, saved[0]);
            restore(level, targetPos, saved[1]);
            removeDrops(level, realPos);
            removeDrops(level, targetPos);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 断言 1（纯函数）：三条一致性校验
    // ------------------------------------------------------------------

    /**
     * {@link InteractionHandler#validateLock} 的两条校验各挡一类故障，逐条验。
     * <p>
     * 这一条是"顺手修掉的附带缺陷"的回归网。最值得注意的是<b>维度</b>：
     * {@link BlockPos} 只是三个整数，同一组坐标在下界与主世界都会相等，
     * 所以只校验位置挡不住跨维度的残留锁定。
     * <p>
     * <b>为什么这里没有周期</b>：{@code getPeriodIndex()} 是附带缺陷③说的"死数据"，
     * 但让<b>它参与判定</b>是错的解法——锁定的语义是"玩家看到的是哪个方块"，
     * 时钟翻页不该让正在挖的那一块改成按原版掉落（详见 {@code validateLock} 的说明）。
     * 第一版确实把它写成了拒绝条件，A/B 阶段推翻了。周期现在只用于诊断。
     * <p>
     * A/B：把 {@code validateLock} 里的维度判断删掉，则 {@code wrongDim} 会被判成 USABLE，
     * 这条 FAIL。
     */
    private static void checkLockValidation(List<String> out, ServerLevel level, BlockPos pos,
                                            BlockState source, BlockState target) {
        long period = MutationEventHandler.displayPeriodIndex(level);
        ResourceKey<Level> dim = level.dimension();
        List<String> failures = new ArrayList<>();

        // 正确锁定 → USABLE（这一条不过说明自测的前提就错了）
        BreakData good = new BreakData();
        good.start(target, period, pos, dim);
        if (InteractionHandler.validateLock(good, pos, dim) != InteractionHandler.LockVerdict.USABLE) {
            failures.add("valid lock rejected");
        }

        // 位置不对 → STALE（上一次挖掘的锁定泄漏到别的方块）
        BreakData wrongPos = new BreakData();
        wrongPos.start(target, period, pos, dim);
        if (InteractionHandler.validateLock(wrongPos, pos.offset(1, 0, 0), dim)
                != InteractionHandler.LockVerdict.STALE) {
            failures.add("wrong position accepted");
        }

        // 维度不对 → STALE（同一组坐标、另一个世界；这是附带缺陷①）
        BreakData wrongDim = new BreakData();
        wrongDim.start(target, period, pos, Level.NETHER);
        if (InteractionHandler.validateLock(wrongDim, pos, dim) != InteractionHandler.LockVerdict.STALE) {
            failures.add("wrong dimension accepted (the P0-4 collateral defect)");
        }

        // 周期翻页 → 仍然 USABLE（这是刻意的：锁定压过时钟漂移，见方法注释）
        BreakData crossedPeriod = new BreakData();
        crossedPeriod.start(target, period - 1, pos, dim);
        if (InteractionHandler.validateLock(crossedPeriod, pos, dim) != InteractionHandler.LockVerdict.USABLE) {
            failures.add("a lock that crosses a period boundary was rejected"
                    + " (honoring what the player saw matters more than clock drift)");
        }

        // 没有锁定 → NO_LOCK（不能和"陈旧"混为一谈：前者什么都不用做，后者要清理）
        if (InteractionHandler.validateLock(new BreakData(), pos, dim)
                != InteractionHandler.LockVerdict.NO_LOCK) {
            failures.add("empty lock not reported as NO_LOCK");
        }

        out.add("[break] lock validation (position / dimension; period must NOT invalidate): "
                + (failures.isEmpty() ? "PASS" : "FAIL (" + String.join("; ", failures) + ")"));
    }

    // ------------------------------------------------------------------
    // 断言 2（核心）：原版管线对着目标方块跑完
    // ------------------------------------------------------------------

    /**
     * 核心断言：给假玩家装上一份"真实方块 = 探针甲、可见目标 = 探针乙"的锁定，
     * 然后走生产的 {@link InteractionHandler#performBreak}，检验四件事同时成立：
     * <ol>
     *   <li>目标探针真的被写进过世界（{@code playerWillDestroy} 被调用）；
     *   <li>{@code onDestroyedByPlayer} 被调用（旧实现直接 {@code setBlock(AIR)}，绕过它）；
     *   <li>{@code playerDestroy} 被调用（旧实现自己拼 {@code ItemEntity}，自定义覆写全失效）；
     *   <li>{@code playerDestroy} 写出的钻石<b>真的出现在世界上</b>（分开计数见探针类注释）。</li>
     * </ol>
     * 另外掉落数量必须与探针写进世界的次数一致——双向判据，见下面的注释。
     * <p>
     * <b>探测的范围</b>：钻石掉在 {@code realPos}（目标是在那里被破坏的），
     * 所以这一条数 {@code realPos}。后面两条断言数 {@code targetPos}，因为那时破坏的是目标位置。
     * 第一版三条都数同一个位置，于是前一条留下的钻石被后一条数进去（实测 diamonds=2），
     * 两条断言同时 FAIL——不是被测代码的问题，是断言把不同位置的现场混在一起了。
     */
    private static void checkVanillaPipelineRunsOnTarget(List<String> out, ServerLevel level, ServerPlayer fake,
                                                         BlockPos realPos, BlockPos targetPos,
                                                         BlockState source, BlockState target) {
        BreakProbeBlock.resetCounters();
        long period = MutationEventHandler.displayPeriodIndex(level);
        fake.getData(ModAttachments.BREAK_DATA).start(target, period, realPos, level.dimension());

        Set<UUID> before = currentDrops(level, realPos, targetPos);
        boolean intercepted = InteractionHandler.performBreak(level, fake, realPos, source, true);
        BreakProbeBlock.Counters c = BreakProbeBlock.counters();
        List<ItemEntity> fresh = newDrops(level, realPos, targetPos, before);
        int diamonds = countDiamonds(fresh);
        // 掉落必须出现在被破坏的那一格（realPos），不是别的什么地方
        boolean droppedAtSite = fresh.stream().anyMatch(e -> e.blockPosition().equals(realPos));
        boolean becameAir = level.getBlockState(realPos).isAir();
        boolean lockConsumed = !fake.getData(ModAttachments.BREAK_DATA).isActive();

        List<String> failures = new ArrayList<>();
        if (!intercepted) {
            failures.add("not intercepted");
        }
        if (!becameAir) {
            failures.add("real block still present: " + level.getBlockState(realPos));
        }
        if (c.willDestroy() == 0) {
            failures.add("playerWillDestroy not called (target never entered the world?)");
        }
        if (c.destroyedByPlayer() == 0) {
            failures.add("onDestroyedByPlayer not called");
        }
        if (c.playerDestroy() == 0) {
            failures.add("playerDestroy not called (custom drop override lost)");
        }
        if (c.dropsSpawned() == 0) {
            failures.add("playerDestroy ran but its drop never reached the world");
        }
        // 掉落数量必须与"探针把掉落写进世界的次数"一致。这一条让断言变成双向的：
        // 少了说明自定义掉落丢了；多了说明有别的东西也在掉钻石（例如真实方块那份被重复结算）。
        if (diamonds != c.dropsSpawned()) {
            failures.add("world gained " + diamonds + " diamond(s) but the target probe spawned "
                    + c.dropsSpawned() + " - the drops and the custom override disagree");
        }
        if (!droppedAtSite) {
            failures.add("drops did not land at the broken position " + realPos.toShortString());
        }
        if (!lockConsumed) {
            failures.add("lock not cleared (next break would reuse a stale target)");
        }

        out.add("[break] vanilla pipeline runs on the VISIBLE TARGET"
                + " (playerWillDestroy/onDestroyedByPlayer/playerDestroy/spawnAfterBreak,"
                + " custom drops reaching the world): "
                + (failures.isEmpty() ? "PASS" : "FAIL (" + String.join("; ", failures) + ")")
                + " [probe " + c.format() + " diamonds=" + diamonds + "]");
    }

    // ------------------------------------------------------------------
    // 断言 3（反证 / 防空跑）：探针本身确实会响
    // ------------------------------------------------------------------

    /**
     * 反证：绕开本模组，直接让原版 {@code destroyBlock} 破坏一块目标探针，
     * 探针的五个计数必须全部大于 0，而且要真的掉出钻石。
     * <p>
     * 没有这一条，断言 2 有可能是<b>假通过</b>——万一探针的覆写因为签名变了压根没被调用
     * （本项目在 {@code AnchorNormalizeAudit} 上踩过两次这类空断言），
     * 断言 2 的"计数 > 0"就会永远失败，或者更糟：如果改成"只断方块变空气"，就会永远通过。
     * 这一条把"探针是活的"单独钉死，于是断言 2 的失败一定是被测代码的问题。
     * <p>
     * {@code spawnAfterBreak} 这一项<b>依赖探针自己的 {@code playerDestroy} 去调它</b>
     * （原版只在 {@code super.playerDestroy} 的默认实现里调）。第一版控制组断言它 {@code > 0}
     * 而探针没调，于是恒为 0——A/B 阶段正确 FAIL，属于"断言写错"而不是被测代码有问题。
     */
    private static void checkCounterObservationIsNotVacuous(List<String> out, ServerLevel level, ServerPlayer fake,
                                                            BlockPos realPos, BlockPos targetPos,
                                                            BlockState source, BlockState target) {
        BreakProbeBlock.resetCounters();
        Set<UUID> before = currentDrops(level, realPos, targetPos);
        boolean destroyed = fake.gameMode.destroyBlock(targetPos);
        BreakProbeBlock.Counters c = BreakProbeBlock.counters();
        int diamonds = countDiamonds(newDrops(level, realPos, targetPos, before));

        // spawnAfterBreak 只在"可采集且确实移除了"时由原版调用，所以它跟着 destroyed 一起判。
        boolean ok = destroyed
                && c.willDestroy() > 0
                && c.destroyedByPlayer() > 0
                && c.playerDestroy() > 0
                && c.afterBreak() > 0
                && c.dropsSpawned() > 0
                && diamonds == c.dropsSpawned();
        out.add("[break] control: bare vanilla destroyBlock on the probe fires every callback"
                + " (guards against a vacuous assertion above): "
                + (ok ? "PASS" : "FAIL")
                + " destroyed=" + destroyed + " diamonds=" + diamonds + " [probe " + c.format() + "]");
    }

    // ------------------------------------------------------------------
    // 断言 4：doTileDrops 真的管住了产出
    // ------------------------------------------------------------------

    /**
     * {@code doTileDrops=false} 时不该有产出。
     * <p>
     * 这一条正是旧实现漏掉 {@code GameRules.RULE_DOBLOCKDROPS} 的直接体现：它自己
     * {@code addFreshEntity}，规则看都不看，于是"关了掉落照样掉"。
     * <p>
     * <b>它守的是哪条路径，要说清楚</b>：探针的掉落走 {@code Block.popResource}，
     * 而 {@code popResource} 内部就门控了 {@code doTileDrops}
     * （见原版 {@code Block#popResource(Level,BlockPos,ItemStack)}）。所以这条断言现在钉的<b>不是</b>
     * "探针会不会自己看规则"，而是"种下去的掉落有没有经过原版那个入口"——
     * 也就是"重派发确实跑了原版管线、而不是我们又自己 new 了一个 ItemEntity"。
     * <p>
     * 因此断言拆成两半才有意义：
     * <ul>
     *   <li>{@code playerDestroy} 仍被调用（{@code > 0}）——证明这次破坏真的走了管线，
     *       否则下面"没有钻石"可能只是因为压根什么都没发生（关掉 {@code performBreak}
     *       也会让它是 0，于是这条会 FAIL 而不是静默 PASS）；</li>
     *   <li>世界上没有钻石——这才是"产出被规则管住"的判据。</li>
     * </ul>
     */
    private static void checkDoTileDropsGatesOutput(List<String> out, ServerLevel level, ServerPlayer fake,
                                                    BlockPos realPos, BlockPos targetPos,
                                                    BlockState source, BlockState target,
                                                    GameRules.BooleanValue doTileDrops) {
        // 注意：这一条<b>不</b>把"玩家 {@code playerDestroy} 被调用"当判据（见下）。
        doTileDrops.set(false, level.getServer());
        BreakProbeBlock.resetCounters();
        long period = MutationEventHandler.displayPeriodIndex(level);
        fake.getData(ModAttachments.BREAK_DATA).start(target, period, realPos, level.dimension());

        Set<UUID> before = currentDrops(level, realPos, targetPos);
        InteractionHandler.performBreak(level, fake, realPos, source, true);
        BreakProbeBlock.Counters c = BreakProbeBlock.counters();
        int diamonds = countDiamonds(newDrops(level, realPos, targetPos, before));

        // 防空跑守卫必须<b>只</b>回答"这次破坏到底有没有发生"，不能顺手要求"走了原版管线"——
        // 后者正是被测的性质，把它塞进守卫会让守卫失败、把真正的判据（掉落有没有出现）盖掉。
        // 第一版就是这么写的：A/B 打开旧管线后这条报的是"pipeline did not run at all"，
        // 而 diamonds 明明是对的 0。旧实现的典型表现恰恰是<b>管线没走、掉落照样出</b>，
        // 于是"守卫失败"把"判据也失败"顶掉了，回归网失去鉴别力。观测到过一次。
        boolean broke = level.getBlockState(realPos).isAir();
        boolean sameBlock = level.getBlockState(targetPos).is(ModBlocks.BREAK_PROBE_TARGET.get());
        List<String> failures = new ArrayList<>();
        if (!broke) {
            failures.add("nothing was broken - assertion would be vacuous");
        }
        if (!sameBlock) {
            failures.add("the untouched target probe at " + targetPos.toShortString() + " changed, now "
                    + level.getBlockState(targetPos) + " - the site is not what this test assumes");
        }
        if (diamonds != 0) {
            failures.add("drops appeared despite doTileDrops=false: " + diamonds);
        }
        // 单独记"有没有走原版管线"：它是诊断信息而不是判据。旧管线下这里会是 0，
        // 而掉落数会是非 0——两个数字放在一起，"关了掉落照样掉"一眼就能看出来。
        boolean viaVanilla = c.playerDestroy() > 0;
        out.add("[break] doTileDrops=false suppresses output: "
                + (failures.isEmpty() ? "PASS" : "FAIL (" + String.join("; ", failures) + ")")
                + " diamonds=" + diamonds + " broken=" + broke + " viaVanillaPipeline=" + viaVanilla);
    }

    // ------------------------------------------------------------------
    // 现场与工具
    // ------------------------------------------------------------------

    /** 把两格恢复成探针，并清掉上一次断言留下的掉落物。 */
    private static void resetSite(ServerLevel level, BlockPos realPos, BlockPos targetPos,
                                  BlockState source, BlockState target) {
        removeDrops(level, realPos);
        removeDrops(level, targetPos);
        level.setBlockAndUpdate(realPos, source);
        level.setBlockAndUpdate(targetPos, target);
    }

    private static void restore(ServerLevel level, BlockPos pos, BlockState original) {
        level.setBlockAndUpdate(pos, original == null ? Blocks.AIR.defaultBlockState() : original);
    }

    /** 破坏之前先记下现场已有的掉落物，破坏之后用差集判"这次新掉了什么"。 */
    private static Set<UUID> currentDrops(ServerLevel level, BlockPos realPos, BlockPos targetPos) {
        Set<UUID> ids = new HashSet<>();
        for (ItemEntity item : dropEntities(level, realPos, targetPos)) {
            ids.add(item.getUUID());
        }
        return ids;
    }

    /** 这次破坏<b>新产生</b>的掉落物。用差集而不是"数盒子里有几颗"，位置歧义就消失了。 */
    private static List<ItemEntity> newDrops(ServerLevel level, BlockPos realPos, BlockPos targetPos,
                                             Set<UUID> before) {
        List<ItemEntity> fresh = new ArrayList<>();
        for (ItemEntity item : dropEntities(level, realPos, targetPos)) {
            if (!before.contains(item.getUUID())) {
                fresh.add(item);
            }
        }
        return fresh;
    }

    private static List<ItemEntity> dropEntities(ServerLevel level, BlockPos realPos, BlockPos targetPos) {
        return level.getEntitiesOfClass(ItemEntity.class, dropArea(realPos, targetPos));
    }

    private static int countDiamonds(List<ItemEntity> drops) {
        int count = 0;
        for (ItemEntity item : drops) {
            ItemStack stack = item.getItem();
            if (stack.is(Items.DIAMOND)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 清掉探针附近所有掉落物。
     * <p>
     * 不能只清"钻石"：测试方块如果被别的东西打碎会留下别的物品，而残留物会让
     * "这次新掉了什么"的差集看起来对，实际现场是脏的。整个清干净更省心，代价也只是删几个实体。
     */
    private static void removeDrops(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos).inflate(DROP_CLEAR_RADIUS);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            item.discard();
        }
    }
}
