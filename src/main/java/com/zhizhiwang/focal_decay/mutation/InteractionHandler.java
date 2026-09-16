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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // ------------------------------------------------------------------
    // 客户端显示刻回报（2026-09-17）
    // ------------------------------------------------------------------

    /**
     * 客户端回报的"它看到的显示刻"。
     *
     * @param pos      交互位置：把回报绑定到这一次交互，避免陈旧回报影响别处的解析
     * @param period   客户端解析用的显示刻
     * @param gameTick 收到回报时的服务端 gameTick（算有效期）
     */
    private record ClientView(long pos, long period, long gameTick) {
    }

    private static final Map<UUID, ClientView> CLIENT_VIEWS = new ConcurrentHashMap<>();

    /** 回报的有效期（tick）：回报紧跟在交互包前面发出，正常只差 0~1 刻。 */
    public static final int CLIENT_VIEW_TTL_TICKS = 40;

    /**
     * "刚刚看到的显示刻"的时效（tick），比 {@link #CLIENT_VIEW_TTL_TICKS} 严得多：
     * 放置方块用的回报必须来自<b>同一次右键</b>（`useItemOn` 与随后的放置同刻发生），
     * 放宽到几十刻就可能拿到上一秒的旧读数、把诞生周期记到别的时刻去。
     */
    private static final int CLIENT_VIEW_RECENT_TICKS = 5;

    /**
     * 允许的时钟偏差（tick）。客户端最多可能领先多少：它自走 20 TPS，而服务端每 20 <b>服务端刻</b>
     * 才校一次；服务端掉到 4 TPS 时这个窗口能堆到 ~100 刻。超过它只能是谎报或串线。
     * <p>
     * 注意这个上限最终会换算成<b>周期</b>偏差（见 {@link #periodWithinSkew}），
     * 所以即使放宽到 5 秒，默认配置下也只放行 ±1 个周期。
     */
    public static final int MAX_CLIENT_SKEW_TICKS = 100;

    /** 记录一次客户端回报（由 {@link com.zhizhiwang.focal_decay.network.SyncClientViewPacket} 调用）。 */
    public static void recordClientView(ServerPlayer player, long packedPos, long period) {
        CLIENT_VIEWS.put(player.getUUID(), new ClientView(packedPos, period, player.serverLevel().getGameTime()));
    }

    /** 玩家退出时清掉回报：条目是按 UUID 存的，不清理会在长跑服务器上慢慢堆积。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CLIENT_VIEWS.remove(player.getUUID());
        }
    }

    /**
     * 该玩家"刚刚看到的显示刻"，不带位置条件（{@link #interactionPeriod} 要位置对得上，
     * 而放置方块时回报的位置是<b>被点击的那一格</b>、新方块落在它旁边，位置必然不同）。
     * <p>
     * 只给"记录诞生周期"用：那是"这块新方块出生于玩家看到的哪一刻"，用同一个 tick 里
     * 刚发生的右键回报足够准。没有回报或已经过期就返回 {@link Long#MIN_VALUE}，让调用方退回服务端读数。
     */
    public static long recentClientPeriod(Entity player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return Long.MIN_VALUE; // 非玩家放置（活塞/结构/生成器……）：没有客户端读数
        }
        ClientView view = CLIENT_VIEWS.get(serverPlayer.getUUID());
        if (view == null) {
            return Long.MIN_VALUE;
        }
        long age = serverPlayer.serverLevel().getGameTime() - view.gameTick();
        if (age < 0 || age > CLIENT_VIEW_RECENT_TICKS) {
            return Long.MIN_VALUE;
        }
        return view.period();
    }

    /**
     * 本次交互用哪一个显示刻：客户端回报优先（通过校验时），否则用服务端自己的。
     * <p>
     * <b>为什么值得这么做</b>：玩家操作的是"他看到的东西"。客户端的指针与服务端差一个周期时，
     * 用服务端的周期去解析，就会把玩家看到的那个方块换成另一个——掉落对不上只是表象。
     */
    private static long interactionPeriod(ServerLevel level, ServerPlayer player, BlockPos pos) {
        long serverPeriod = MutationEventHandler.displayPeriodIndex(level);
        ClientView view = CLIENT_VIEWS.get(player.getUUID());
        if (view == null) {
            return serverPeriod;
        }
        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        return chooseInteractionPeriod(serverPeriod, view.period(), level.getGameTime() - view.gameTick(),
                view.pos() == pos.asLong(), worldData.getClockSpeed(), MutationHelper.configBaseInterval());
    }

    /**
     * 采用哪一根指针（<b>纯函数</b>，供自测）：三条独立的门槛都过了才采用客户端的回报。
     * <ol>
     *   <li><b>位置对得上</b>——回报是绑定到某一次交互的，位置不符说明它是别处留下的；</li>
     *   <li><b>足够新</b>——交互包紧跟回报，过期说明两者不是同一次操作；</li>
     *   <li><b>偏差在物理可能范围内</b>——见 {@link #periodWithinSkew}。</li>
     * </ol>
     * 任何一条不过就退回服务端自己的显示刻：宁可用自己的，也不用一个来路不明的周期。
     */
    public static long chooseInteractionPeriod(long serverPeriod, long clientPeriod, long reportAgeTicks,
                                               boolean samePos, double speed, long interval) {
        if (!samePos || reportAgeTicks < 0 || reportAgeTicks > CLIENT_VIEW_TTL_TICKS) {
            return serverPeriod;
        }
        return periodWithinSkew(serverPeriod, clientPeriod, speed, interval) ? clientPeriod : serverPeriod;
    }

    /**
     * 客户端回报的显示刻是否在"物理上可能"的偏差内（<b>纯函数</b>，供自测）。
     * <p>
     * 允许的周期偏差 = {@code ceil(|speed| × MAX_CLIENT_SKEW_TICKS / interval) + 1}：
     * 客户端时钟最多差 MAX_CLIENT_SKEW_TICKS 刻，乘上流速倍率再除以周期长度就是它能跨过的周期数，
     * 末尾 +1 是取整余量。倍率为 0（冻结）时两端都取 offset，偏差只可能来自取整。
     * <p>
     * 用"上界比较"而不是 {@code Math.abs(a - b) <= bound}：后者在客户端报来
     * {@code Long.MIN_VALUE} 这类荒唐值时减法会溢出成负数，反而判成通过。
     */
    public static boolean periodWithinSkew(long serverPeriod, long clientPeriod, double speed, long interval) {
        long bound = (long) Math.ceil(Math.abs(speed) * MAX_CLIENT_SKEW_TICKS / Math.max(1L, interval)) + 1L;
        return clientPeriod <= serverPeriod + bound && clientPeriod >= serverPeriod - bound;
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
        // 服务端侧的事件实体一定是 ServerPlayer（回报表按 UUID 索引，需要一个稳定的身份）
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
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

        // 统一入口抽取目标（与右键训练、渲染预览、锚固化同一公式）。
        // 周期取"客户端看到的那一刻"：客户端的 gameTime 是本地自走的，服务端每 20 tick 才校一次，
        // 掉帧时两者会差出一个周期——用服务端的周期解析就等于换掉玩家正在挖的那个方块。
        long periodIndex = interactionPeriod(serverLevel, player, pos);
        BlockState target = MutationTargets.resolveServer(serverLevel, pos, state, periodIndex);
        breakData.start(target, periodIndex, pos);
        trace("left-click " + pos.toShortString() + " stage=" + stage + " real=" + id(state)
                + " target=" + id(target));
        tracePeriodEcho(serverLevel, pos, state, target, periodIndex);
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
        long periodIndex = interactionPeriod(serverLevel, player, pos);
        BlockState target = conversionSource
                ? MutationTargets.resolveServer(serverLevel, pos, state, periodIndex) : state;
        boolean convert = conversionSource && target.getBlock() != state.getBlock();

        // 诊断日志一律用 ASCII：控制台是 GBK，写中文会变成乱码
        trace("right-click " + pos.toShortString() + " real=" + id(state)
                + " stage=" + stage
                + " hand=" + event.getHand() + " creative=" + player.isCreative()
                + " observerOnline=" + !mutationsActive(serverLevel)
                + " conversionSource=" + conversionSource
                + " target=" + id(target) + " convert=" + convert);
        if (conversionSource) {
            tracePeriodEcho(serverLevel, pos, state, target, periodIndex);
        }

        if (!convert) {
            return; // 非转换源，或本周期没抽中：方块没变，走原版交互
        }

        // 先真实转换（flag 3 = 通知客户端 + 触发邻块更新），再让目标方块接管这次右键
        serverLevel.setBlock(pos, target, 3);

        // 转换后给方块重新记一个"诞生周期"（**显示时钟**：玩家看到的那根指针，见 birthPeriodIndex）：
        // 目标现在是这个位置的新身份，本周期内不再按新身份重掷（否则客户端下一帧就会显示
        // 下一个目标，表现为"右键一次变一次"的抖动），下一个显示周期才继续漂移。
        //
        // 这里必须用刚才解析用的那一刻（periodIndex）而不是服务端自己的读数：闸门比的就是显示刻，
        // 而客户端的显示刻可能领先/落后服务端几个周期（倍率越高差得越多）。两处不同域时，
        // 闸门会立刻打开——右键长按就能把一个方块来回转换。
        long birthPeriod = MutationEventHandler.birthPeriodIndex(serverLevel, periodIndex);
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

    /**
     * 诊断：这一次解析用的是客户端回报的显示刻、且它服务端<b>本来会算出别的方块</b>时打一行。
     * <p>
     * 这正是以前那个"挖到的和看到的不一样"的窗口，也是回报机制唯一能观测到的价值。
     * 默认只在 {@code /focaldecay trace true} 之后打——正常情况下它每个周期边界最多出现一次，
     * 但没人想让它常驻日志。想量"偏差到底有多大"就把它打开玩一局。
     */
    private static void tracePeriodEcho(ServerLevel level, BlockPos pos, BlockState state,
                                        BlockState usedTarget, long usedPeriod) {
        if (!traceEnabled) {
            return;
        }
        long serverPeriod = MutationEventHandler.displayPeriodIndex(level);
        if (usedPeriod == serverPeriod) {
            return;
        }
        BlockState serverTarget = MutationTargets.resolveServer(level, pos, state, serverPeriod);
        if (serverTarget == usedTarget) {
            return; // 周期不同但结果相同：没有可观测差别
        }
        trace("  period echo: client period=" + usedPeriod + " server period=" + serverPeriod
                + " -> kept the client's, otherwise " + id(serverTarget) + " instead of " + id(usedTarget));
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
