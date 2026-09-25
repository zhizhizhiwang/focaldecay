package com.zhizhiwang.focal_decay.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 挖掘路径自测用的<b>探针方块</b>（BACKLOG P0-4 作者裁定：允许注册测试方块）。
 * <p>
 * <b>为什么非要有它</b>：要验证的性质是"原版破坏管线确实对着<b>目标方块</b>跑完了"，
 * 而这件事只体现在原版调用哪几个回调上。用现成方块没法观测——只能间接看掉落，
 * 而掉落受工具、gamerule、随机池影响，判据很脆。探针把"被调用了"变成计数，
 * 于是断言变成确定性的整数比较。
 * <p>
 * 覆盖四个回调，正好对应旧"手工管线"漏掉的那几步：
 * <ul>
 *   <li>{@code playerWillDestroy}——旧实现完全没调；</li>
 *   <li>{@code onDestroyedByPlayer}——旧实现直接 {@code setBlock(AIR)}，绕过了它；</li>
 *   <li>{@code playerDestroy}——旧实现自己拼 {@code ItemEntity}，自定义覆写全失效；</li>
 *   <li>{@code spawnAfterBreak}——旧实现没调（红石矿额外掉落那类效果）。</li>
 * </ul>
 * 另有 {@code dropsFromPlayerDestroy} 一个整数：它是"目标方块写给世界的掉落"，
 * 与"世界上真的出现了什么"分开记，这样"玩家的 {@code playerDestroy} 被调了但掉落没进世界"
 * 也能被抓出来。
 * <p>
 * <b>它不进创造栏、没有物品形态、没有配方</b>（见 {@code ModBlocks} 与 {@code ModCreativeTabs}）——
 * 只用来在自测里摆两块，玩家正常游戏接触不到。
 * <p>
 * <b>它也没有进任何原版方块标签</b>（{@code mineable/*} 之类）。第一版注释写了"仍登记在
 * {@code mineable/pickaxe} 与 {@code needs_stone_tool} 里，免得数据生成报挖掘工具标签的警告"
 * ——那是**没核实的断言**：本项目的 {@code ModBlockTagsProvider} 根本不生成 {@code mineable/*}
 * （grep 一遍就知道），{@code runData} 也确实没有任何生成物变化。
 * 测试方块不需要那些标签；真正要防的是"玩家拿到它"，靠的是没有 {@code BlockItem}，不是标签。
 * <p>
 * <b>刻意不实现 {@code canHarvestBlock}</b>：那样它的掉落就完全由 {@code playerDestroy} 说了算，
 * 测试不需要在假玩家身上准备工具，也不受 {@code requiresCorrectToolForDrops} 影响。
 * 代价是它不校验"工具门控"那条路径——那条由 {@code [selftest]} 的原有断言覆盖。
 */
public class BreakProbeBlock extends Block {

    /**
     * 目标探针（{@link #isTargetProbe()} 为 {@code true}）被 {@code playerDestroy} 时掉什么。
     * 用原版物品而不是本模组物品：断言"世界上多了一颗钻石"不依赖任何模组注册表。
     */
    private static final ItemStack TARGET_DROP = new ItemStack(Items.DIAMOND);

    private final boolean targetProbe;

    // ---- 观测计数器（自测读，别的什么都不依赖） ----

    private static final AtomicInteger WILL_DESTROY = new AtomicInteger();
    private static final AtomicInteger DESTROYED_BY_PLAYER = new AtomicInteger();
    private static final AtomicInteger PLAYER_DESTROY = new AtomicInteger();
    private static final AtomicInteger AFTER_BREAK = new AtomicInteger();
    /** {@code playerDestroy} 里写进世界的钻石数量（与 {@link #PLAYER_DESTROY} 分开，见类注释）。 */
    private static final AtomicInteger DROPS_SPAWNED = new AtomicInteger();

    /** 一次自测读出来的五个计数。 */
    public record Counters(int willDestroy, int destroyedByPlayer, int playerDestroy,
                           int afterBreak, int dropsSpawned) {
        public String format() {
            return "willDestroy=" + willDestroy
                    + " onDestroyedByPlayer=" + destroyedByPlayer
                    + " playerDestroy=" + playerDestroy
                    + " spawnAfterBreak=" + afterBreak
                    + " dropsSpawned=" + dropsSpawned;
        }
    }

    public static Counters counters() {
        return new Counters(WILL_DESTROY.get(), DESTROYED_BY_PLAYER.get(),
                PLAYER_DESTROY.get(), AFTER_BREAK.get(), DROPS_SPAWNED.get());
    }

    /** 每个断言之前清零：计数器是全局的，不清就会把上一条断言的调用算进来（假通过）。 */
    public static void resetCounters() {
        WILL_DESTROY.set(0);
        DESTROYED_BY_PLAYER.set(0);
        PLAYER_DESTROY.set(0);
        AFTER_BREAK.set(0);
        DROPS_SPAWNED.set(0);
    }

    /** 只有"目标探针"的 {@code playerDestroy} 才掉钻石，这样计数能区分两块探针。 */
    public boolean isTargetProbe() {
        return targetProbe;
    }

    public BreakProbeBlock(boolean targetProbe) {
        super(BlockBehaviour.Properties.of()
                .strength(1.0f)
                .sound(SoundType.STONE));
        this.targetProbe = targetProbe;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        WILL_DESTROY.incrementAndGet();
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, net.minecraft.world.level.material.FluidState fluid) {
        DESTROYED_BY_PLAYER.incrementAndGet();
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              net.minecraft.world.level.block.entity.BlockEntity blockEntity, ItemStack tool) {
        PLAYER_DESTROY.incrementAndGet();
        // 刻意<b>不</b>调 super：super（{@code BlockBehaviour#playerDestroy}）走的是战利品表，
        // 而这条断言要的正是"覆写的掉落生效了"。旧的手工管线恰好在这里失效——
        // 它自己 {@code getDrops} + {@code addFreshEntity}，完全绕过本方法，
        // 于是所有自定义 {@code playerDestroy} 的方块（容器溢出内容物是最典型的一个）都算错。
        //
        // 这里显式调 {@code spawnAfterBreak}，因为原版只在 {@code super.playerDestroy} 的
        // 默认实现里调它（{@code BlockBehaviour#playerDestroy} → {@code state.spawnAfterBreak(...)}）。
        // 不调的话"管道有没有走到 spawnAfterBreak"就<b>验不到</b>——第一版就是这么写的，
        // 结果控制组断言它>0 而实际恒为 0，A/B 阶段抓出来的。
        spawnAfterBreak(state, (ServerLevel) level, pos, tool, true);
        if (this.targetProbe) {
            // 用 {@code Block.popResource} 而不是自己 new ItemEntity + addFreshEntity：
            // popResource 内部门控了 doTileDrops（原版 {@code Block#popResource(Level,BlockPos,ItemStack)}），
            // 自建实体则绕过它、变成"关了掉落照样掉"——那正是旧实现的缺陷之一。
            // 用官方入口，探针的掉落就自动是 gamerule 中立的。
            popResource(level, pos, TARGET_DROP.copy());
            DROPS_SPAWNED.incrementAndGet();
        }
    }

    @Override
    protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool,
                                   boolean dropExperience) {
        AFTER_BREAK.incrementAndGet();
    }
}
