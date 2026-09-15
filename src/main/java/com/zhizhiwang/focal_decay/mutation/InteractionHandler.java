package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.attachment.BreakData;
import com.zhizhiwang.focal_decay.attachment.ModAttachments;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;

/**
 * 交互与转换（设计大纲 §5）：
 *  - 挖掘开始：锁定目标方块状态与周期索引（BreakData attachment）
 *  - 方块破坏：取消默认掉落，将方块真实转换为目标状态并生成掉落
 */
public class InteractionHandler {

    /**
     * 重派发右键交互的旁路标志。
     * <p>
     * 转换后我们会调用 {@code ServerPlayerGameMode#useItemOn} 让<b>目标方块</b>接管这次右键，
     * 而那个方法会再次触发 {@link PlayerInteractEvent.RightClickBlock}。没有这个标志就会无限递归。
     * 用 ThreadLocal 而不是玩家标记：重派发是同线程同步调用，作用域天然只覆盖这一次调用，
     * 也不会在异常路径上留下脏状态。
     */
    private static final ThreadLocal<Boolean> REDISPATCHING = ThreadLocal.withInitial(() -> false);

    /**
     * 诊断开关（{@code /focaldecay trace true}）。开启后右键判定会打日志，
     * 用于定位"交互没按可见目标响应"这类只能实机复现的问题。
     */
    public static boolean traceEnabled;

    private static void trace(String message) {
        if (traceEnabled) {
            FocalDecay.LOGGER.info("[trace] {}", message);
        }
    }

    /** 当前是否允许失焦转换（观测者在线时一切转换停止）。 */
    private static boolean mutationsActive(ServerLevel level) {
        return !FocalDecayWorldData.get(level.getServer()).isObserverOnline();
    }

    /** 当前阶段。 */
    private static int currentStage(ServerLevel level) {
        long days = FocalDecayWorldData.get(level.getServer()).getDays();
        return MutationHelper.currentStage(days);
    }

    /** 挖掘开始，记录锁定数据。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Player player = event.getEntity();
        if (player.isCreative()) {
            return; // 创造模式破坏保持原版行为，不执行转换
        }
        BlockPos pos = event.getPos();
        BlockState state = serverLevel.getBlockState(pos);
        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        if (!mutationsActive(serverLevel)) {
            breakData.clear(); // 失焦终止：清掉可能的陈旧锁定
            return; // 失焦终止：不再锁定突变目标
        }

        int stage = currentStage(serverLevel);

        // 带方块实体的方块、空气、豁免方块、不参与突变的形态类：不参与转换
        if (!MutationIndexes.get(serverLevel.dimension()).isSource(state.getBlock())) {
            breakData.clear(); // 非转换源（门/楼梯/栅栏等未登记形态类）：清掉陈旧锁定，避免掉落泄漏
            return;
        }

        // 统一入口抽取目标（与右键训练、渲染预览、锚固化同一公式，显示时钟）
        long periodIndex = MutationEventHandler.displayPeriodIndex(serverLevel);
        BlockState target = MutationTargets.resolveServer(serverLevel, pos, state);
        breakData.start(target, periodIndex, pos);
        trace("left-click " + pos.toShortString() + " stage=" + stage + " real=" + id(state)
                + " target=" + id(target));
    }

    /**
     * 右键交互：失焦中的方块按<b>可见目标</b>响应。
     * <p>
     * 不做这件事的后果：工作台、切石机、织布机这类"完整立方体 + 有右键行为 + 无方块实体"的方块
     * 在视觉上已经变成别的方块，右键却仍然打开原有界面。这里按与挖掘相同的思路处理——
     * 先把方块真的转换成可见目标，再让<b>目标方块</b>接管这次右键（设计取向 A：你操作的是你看到的那个东西）。
     * <p>
     * <b>创造模式同样处理</b>：客户端的幽灵预览对所有游戏模式都生效，如果只在生存模式转换，
     * 创造模式玩家就会看到"显示成石头、右键却打开合成台"这种前后不一致。挖掘那边可以跳过是因为
     * 创造挖掘本就不产生掉落、无需转换；右键没有这层理由。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (REDISPATCHING.get()) {
            return; // 这是我们自己重派发出来的那一次，交给原版正常处理
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return; // 双手各处理一次会重复转换
        }
        if (!mutationsActive(serverLevel)) {
            return; // 失焦终止
        }

        BlockPos pos = event.getPos();
        BlockState state = serverLevel.getBlockState(pos);
        int stage = currentStage(serverLevel);
        boolean conversionSource = MutationIndexes.get(serverLevel.dimension()).isSource(state.getBlock());
        BlockState target = conversionSource ? MutationTargets.resolveServer(serverLevel, pos, state) : state;
        boolean convert = conversionSource && target.getBlock() != state.getBlock();

        // 诊断日志一律用 ASCII：控制台是 GBK，写中文会变成乱码
        trace("right-click " + pos.toShortString() + " real=" + id(state)
                + " stage=" + stage
                + " hand=" + event.getHand() + " creative=" + player.isCreative()
                + " observerOnline=" + !mutationsActive(serverLevel)
                + " conversionSource=" + conversionSource
                + " target=" + id(target) + " convert=" + convert);

        if (!convert) {
            return; // 非转换源，或本周期没抽中：方块没变，走原版交互
        }

        // 先真实转换（flag 3 = 通知客户端 + 触发邻块更新），再让目标方块接管这次右键
        serverLevel.setBlock(pos, target, 3);

        // 转换后给方块重新记一个"诞生周期"（存储时钟：真实时间轴上它此刻是新的身份）：
        // 目标现在是这个位置的新身份，本周期内不再按新身份重掷（否则客户端下一帧就会显示
        // 下一个目标，表现为"右键一次变一次"的抖动），下一个周期才继续漂移。
        long birthPeriod = MutationEventHandler.storagePeriodIndex(serverLevel);
        MutationPoolManager.get(serverLevel).setBlockBirthPeriod(pos, birthPeriod);
        ModNetwork.sendBirthPeriod(serverLevel, pos, birthPeriod);

        REDISPATCHING.set(true);
        try {
            player.gameMode.useItemOn(player, serverLevel, player.getMainHandItem(),
                    event.getHand(), event.getHitVec());
        } finally {
            REDISPATCHING.set(false);
        }
        trace("  dispatched interaction to target " + id(target));

        // ⚠️ 关键：取消事件后必须显式设置取消结果。
        // RightClickBlock 的 cancellationResult 默认是 InteractionResult.PASS，而
        // ServerPlayerGameMode#useItemOn 的写法是 `if (event.isCanceled()) return event.getCancellationResult();`
        // —— 于是"取消"等于告诉原版"我没处理，继续走"。后果有两层：
        //   1) 它已抓取的 blockstate（转换前的方块）会继续走 useWithoutItem → 打开原有界面（合成台照旧打开）；
        //   2) 拿到 !consumesAction() 后还会去尝试 stack.useOn(...)，即顺手放置手里的方块。
        // FAIL 会干净地终止整条后续流程：方块已由我们转换、交互已由目标方块处理完毕。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    private static String id(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** 方块破坏：执行真实转换。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.isCreative()) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState sourceState = event.getState();
        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        if (!breakData.isActive()) {
            return;
        }
        if (!pos.equals(breakData.getPos())) {
            breakData.clear(); // 位置不匹配：陈旧的锁定（上次挖掘的目标残留）
            return;
        }

        BlockState targetState = breakData.getTargetState();
        breakData.clear();

        if (targetState == null) {
            return;
        }

        // 取消默认掉落与经验，自行处理转换
        event.setCanceled(true);

        // 清除原方块
        serverLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        // 工具传入玩家主手物品：目标方块的"挖掘等级"（requiresCorrectToolForDrops）
        // 由当前可见目标决定——拿对工具才有对应掉落，拿错则无掉落（与原版一致）
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                targetState, serverLevel, pos, null, player, player.getMainHandItem());

        // 生存模式：生成目标方块的掉落物实体与经验
        for (ItemStack drop : drops) {
            net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                    serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
            item.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(item);
        }
        int exp = targetState.getExpDrop(serverLevel, pos, null, player, player.getMainHandItem());
        if (exp > 0) {
            targetState.getBlock().popExperience(serverLevel, pos, exp);
        }

        // 原版 destroyBlock 在 BreakEvent 取消后跳过了 mineBlock 的耐久消耗，这里手动补上：
        // 按"当前可见目标"结算（目标可破坏速度非 0 时扣 2 耐久，与原版一致）
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            held.mineBlock(serverLevel, targetState, pos, player);
        }
        // 还原原版 Block.playerDestroy 的玩家侧行为（被 BreakEvent 取消跳过）：
        // 挖掘统计 + 0.005 饥饿消耗，按"当前可见目标"结算
        player.awardStat(Stats.BLOCK_MINED.get(targetState.getBlock()));
        player.causeFoodExhaustion(0.005F);

        // 铜块失焦突变：概率掉落"硫铜结晶"语义碎片（设计大纲 §11 来源 5）
        if (targetState.getBlock() != sourceState.getBlock()
                && sourceState.is(Blocks.COPPER_BLOCK)
                && serverLevel.random.nextDouble() < FocalDecayConfig.FRAGMENT_COPPER_MUTATION_CHANCE.get()) {
            net.minecraft.world.entity.item.ItemEntity fragment = new net.minecraft.world.entity.item.ItemEntity(
                    serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new ItemStack(ModItems.FRAGMENT_CRYSTAL.get()));
            fragment.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(fragment);
        }
    }

}
