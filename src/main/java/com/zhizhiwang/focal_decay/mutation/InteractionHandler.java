package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.attachment.BreakData;
import com.zhizhiwang.focal_decay.attachment.ModAttachments;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

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
     * 重派发<b>破坏</b>的旁路标志（与右键那个分开）。
     * <p>
     * 为什么不能共用一个：右键路径在重派发期间要置位、破坏路径也要置位，但两条路径的
     * "应该放行哪一个处理器"是<b>不同</b>的问题——共用标志会让右键重派发期间到达的破坏事件
     * 也走旁路放行（虽然实际不会同时发生，但语义上就是错的）。两个标志各自表达自己那一件事。
     */
    private static final ThreadLocal<Boolean> BREAK_REDISPATCHING = ThreadLocal.withInitial(() -> false);

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
        breakData.start(target, periodIndex, pos, serverLevel.dimension());
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

        // 诊断（BACKLOG P1-6 第 15 条）：NeoForge 的 setCanceled 不重置 cancellationResult，
        // 所以<b>更晚</b>注册的 receiveCanceled=true 监听器可以把 FAIL 改成别的值（甚至取消取消）。
        // 那时原版会拿着它早先抓取的旧 blockstate 再跑一遍 useItemOn →
        // 目标方块被 use 两次，或顺手把手里的方块放下去。
        // 我们无法阻止别人这么做，但可以把它变成可诊断的——整合包里出现双重交互时先看这一行。
        if (event.getCancellationResult() != InteractionResult.FAIL) {
            FocalDecay.LOGGER.warn("[focal_decay] right-click cancellation result was changed by another"
                            + " listener at {}: expected {} but got {} -"
                            + " vanilla may now run the interaction a second time",
                    pos.toShortString(), InteractionResult.FAIL, event.getCancellationResult());
        }
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

    /**
     * 方块破坏：把这个位置<b>换成可见目标方块</b>，然后让原版破坏管线对着它跑完。
     * <p>
     * <b>为什么不是"手工补掉落"</b>（2026-09-25 重构，BACKLOG P0-4）：
     * 旧实现在这里 {@code setCanceled(true)} 之后手工复刻原版 {@code destroyBlock} 的收尾
     * （{@code setBlock(AIR)} → {@code Block.getDrops} → 自建 {@code ItemEntity} → {@code getExpDrop}
     * → {@code mineBlock} → {@code awardStat} → {@code causeFoodExhaustion}），
     * 漏掉了原版的八项行为：{@code playerWillDestroy} / {@code onDestroyedByPlayer} / {@code destroy} /
     * {@code playerDestroy}（自定义方块覆写的掉落，例如<b>容器溢出内容物</b>）/ {@code spawnAfterBreak} /
     * {@code BlockDropsEvent} / {@code RULE_DOBLOCKDROPS} / {@code onPlayerDestroyItem}。
     * 这种"手工管线"每补一条边就要永久维护一次，而且没法验证"补全了没有"。
     * <p>
     * <b>现在只替换一个变量：被破坏的是哪个方块。</b>做法是把可见目标真的写进世界，
     * 再重派发 {@code ServerPlayerGameMode#destroyBlock}——原版管线对着一份<b>普通的</b>目标方块状态
     * 走完整流程，于是上面八项以及<b>以后原版或别的模组新加的任何一步</b>都自动正确。
     * 重派发期间 {@link #BREAK_REDISPATCHING} 置位，本处理器直接放行，让原版继续。
     * <p>
     * <b>为什么必须重派发、不能"放进去然后不取消事件"</b>：原版在触发 {@code BreakEvent} 之前
     * 就已经把 {@code blockstate1} 读进局部变量了，{@code playerWillDestroy} / {@code playerDestroy}
     * 用的都是那一份。改了世界也改不了它手里那个引用，结果会是"世界里的方块变了、掉落却按旧方块算"。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (BREAK_REDISPATCHING.get()) {
            return; // 我们自己重派发出来的那一次：交给原版正常处理
        }
        if (event.isCanceled()) {
            return; // 更早的监听器（例如保护类模组）已经否决了这次破坏，尊重它
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // gameMode 字段只在 ServerPlayer 上：重派发原版管线需要一个服务端玩家，
        // 所以这里就把类型收紧（服务端侧的 BreakEvent 实体本来就是 ServerPlayer，
        // 与 onLeftClickBlock 的写法一致）
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isCreative()) {
            return; // 创造模式破坏不产生掉落，无需转换（与旧实现一致）
        }

        BlockState sourceState = event.getState();
        if (performBreak(serverLevel, player, event.getPos(), sourceState, true)) {
            // ⚠️ 必须取消：这次事件面对的是<b>真实</b>方块，而那个方块已经在重派发里被原版
            // 正常破坏掉了。不取消的话原版 {@code destroyBlock} 会接着拿它自己那份旧引用
            // 再走一遍 removeBlock/playerDestroy——方块已被移除，多半静默失败或重复掉落。
            // 取消时原版会把 {@code state}（真实方块）回发给客户端，但世界此刻已经是空气，
            // setBlock(flag 3) 的更新紧随其后，客户端不会闪。
            event.setCanceled(true);
        }
    }

    /**
     * 破坏拦截的<b>可测入口</b>（{@code [break]} 自测直接调它，见 {@code BreakAudit}）。
     * <p>
     * 与事件监听器分开是为了让自测能跑在<b>同一条生产代码路径</b>上：自测只需要一个
     * {@code ServerPlayer}（用 {@code FakePlayer} 即可），不必伪造一个 {@code BlockEvent}，
     * 也不必注册一个假的事件总线。逻辑只写在这里一份。
     *
     * @param swapInTarget 是否真的把目标方块写进世界并重派发原版管线。
     *                     生产路径恒为 {@code true}；自测用 {@code false} 做<b>反证</b>
     *                     （证明"没有这一步时断言确实会 FAIL"，见 §4 的 A/B 硬要求）。
     * @return 是否拦截了这次破坏（调用方据此取消原事件）
     */
    public static boolean performBreak(ServerLevel level, ServerPlayer player, BlockPos pos,
                                       BlockState sourceState, boolean swapInTarget) {
        BreakData breakData = player.getData(ModAttachments.BREAK_DATA);
        LockVerdict verdict = validateLock(breakData, pos, level.dimension());
        if (verdict != LockVerdict.USABLE) {
            if (verdict != LockVerdict.NO_LOCK) {
                breakData.clear(); // 陈旧锁定（跨位置/跨维度）一律清掉，不留着下一次误用
            }
            return false;
        }
        // 周期翻页**不是**拒绝转换的理由，只记一行诊断（见 validateLock 的说明）。
        long currentPeriod = MutationEventHandler.displayPeriodIndex(level);
        if (breakData.getPeriodIndex() != currentPeriod) {
            trace("break lock crosses a period boundary at " + pos.toShortString()
                    + ": locked=" + breakData.getPeriodIndex() + " current=" + currentPeriod
                    + " -> honoring the locked target (that is what the player saw)");
        }

        BlockState targetState = breakData.getTargetState();
        breakData.clear(); // 必须在重派发<b>之前</b>清掉：第二次 BreakEvent 会再次进入本方法
        if (targetState == sourceState) {
            return false; // 本周期没抽中（目标与真实方块相同）：走原版，不必折腾
        }
        if (!swapInTarget) {
            return false; // 自测的反证路径：只走到这里，不写世界
        }

        // ---- 唯一改动世界的那一步：把可见目标放回世界 ----
        // flag 2 = 通知客户端 + 不触发邻块更新。刻意<b>不</b>用右键路径那个 flag 3：
        // 这里紧接着就要把这块拆掉，邻块在几微秒内会被通知两次（一次"变成石头"、
        // 一次"变成空气"），对红石之类的方块是可见的抖动。方块形状没有变化，
        // 邻块本来就不需要为"这里换了种石头"重新计算。
        level.setBlock(pos, targetState, 2);

        // ---- A/B 开关（只给自测用，正常游戏永远不会打开）----
        if (AB_OLD_MANUAL_PIPELINE) {
            oldManualPipelineForAbTest(level, player, pos, targetState, sourceState);
            return true;
        }

        // ---- 让原版对着目标方块跑完整流程 ----
        BREAK_REDISPATCHING.set(true);
        try {
            player.gameMode.destroyBlock(pos);
        } finally {
            BREAK_REDISPATCHING.set(false);
        }

        // 铜块失焦突变：概率掉落"硫铜结晶"语义碎片（设计大纲 §11 来源 5）。
        // 这是本模组额外的语义掉落，不属于原版那条管线，所以留在这里。
        // 放在重派发<b>之后</b>：即使原版因为 canHarvest 判定没有产出，碎片也该照掉。
        if (sourceState.is(Blocks.COPPER_BLOCK)
                && level.random.nextDouble() < FocalDecayConfig.FRAGMENT_COPPER_MUTATION_CHANCE.get()) {
            net.minecraft.world.entity.item.ItemEntity fragment = new net.minecraft.world.entity.item.ItemEntity(
                    level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new ItemStack(ModItems.FRAGMENT_CRYSTAL.get()));
            fragment.setDefaultPickUpDelay();
            level.addFreshEntity(fragment);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // A/B 回归开关（BACKLOG P0-4 的验证要求）
    // ------------------------------------------------------------------

    /**
     * 打开后，{@link #performBreak} 走<b>重构前那套手工管线</b>，而不是重派发原版。
     * <p>
     * <b>为什么要有这段代码</b>：本项目的硬要求是"新增一条断言时，要故意把代码改回坏状态
     * 确认它真的会 FAIL"（{@code AGENTS.md} §4）。P0-4 的断言全是"探针回调被调用了没有"，
     * 而正确答案恰恰是"没有被调用"——这种断言最容易变成永远 PASS 的空断言。
     * 靠手工把代码改回去、跑一遍、再改回来，既容易漏（忘了恢复就提交了），
     * 也没法在以后重构时重跑。所以把它固化成一个只有测试才会打开的开关：
     * <pre>
     *   ./gradlew.bat runServer -Dfocaldecay.abOldBreakPipeline=true
     * </pre>
     * 打开后 {@code [break]} 的四条断言应当出现：核心断言 FAIL、控制组 PASS（它不走本路径）、
     * {@code doTileDrops=false} 那条 FAIL。实测结果记在 {@code docs/progress/2026Q4.md}。
     * <p>
     * 这是<b>唯一</b>一处为了测试而留在生产类里的代码，所以刻意写得显眼、只有静态 final 判断、
     * 且默认关闭——没有配置项、没有命令、运行时无法打开。
     */
    private static final boolean AB_OLD_MANUAL_PIPELINE =
            Boolean.getBoolean("focaldecay.abOldBreakPipeline");

    /**
     * 重构前那套"手工复刻原版收尾"的管线，逐行保留其缺陷，只用于 A/B 回归。
     * <p>
     * 它刻意重犯旧实现的每一个错：手工 {@code setBlock(AIR)}（绕过 {@code onDestroyedByPlayer}）、
     * 自己 {@code getDrops} 后 {@code addFreshEntity}（绕过 {@code playerDestroy} 覆写、
     * {@code BlockDropsEvent} 与 <b>{@code doTileDrops}</b> 规则）、不调 {@code spawnAfterBreak}、
     * 不调 {@code playerWillDestroy}。
     *
     * @see #AB_OLD_MANUAL_PIPELINE
     */
    private static void oldManualPipelineForAbTest(ServerLevel level, ServerPlayer player, BlockPos pos,
                                                   BlockState targetState, BlockState sourceState) {
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        for (ItemStack drop : net.minecraft.world.level.block.Block.getDrops(
                targetState, level, pos, null, player, player.getMainHandItem())) {
            net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                    level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
        int exp = targetState.getExpDrop(level, pos, null, player, player.getMainHandItem());
        if (exp > 0) {
            targetState.getBlock().popExperience(level, pos, exp);
        }
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            held.mineBlock(level, targetState, pos, player);
        }
        player.awardStat(net.minecraft.stats.Stats.BLOCK_MINED.get(targetState.getBlock()));
        player.causeFoodExhaustion(0.005F);
    }

    /** {@link #validateLock} 的判定结果。 */
    public enum LockVerdict {
        /** 可用：位置与维度都对得上。 */
        USABLE,
        /** 根本没有锁定（本周期没抽中，或压根没开始挖）。 */
        NO_LOCK,
        /** 锁定的位置或维度对不上：上一次挖掘、或另一个世界的残留。 */
        STALE
    }

    /**
     * 这份挖掘锁定还能不能用（<b>纯函数</b>，供自测）。
     * <p>
     * 两条校验各挡一类真实故障，缺一不可：
     * <ol>
     *   <li><b>位置</b>——防止上一次挖掘的锁定泄漏到别的方块；</li>
     *   <li><b>维度</b>——{@link BlockPos} 只是三个整数，同一组坐标在下界与主世界都会相等。
     *       以前只校验位置：玩家在主世界开始挖、走进传送门、落地后恰好有方块位于同一组坐标，
     *       就会拿主世界那份锁定去转换下界的方块（BACKLOG P0-4 附带缺陷①）。</li>
     * </ol>
     * <p>
     * <b>周期（{@code getPeriodIndex()}）刻意<b>不</b>参与判定</b>——虽然附带缺陷③说它是死数据，
     * 但"让它参与判定"是错的解法（第一版就是这么写的，A/B 阶段推翻了）：
     * <ul>
     *   <li>锁定的语义是"<b>玩家看到的是哪个方块</b>"。它在挖掘开始那一刻定下来，
     *       而"看得见摸不着"正是这个模组要避免的事。时钟翻页只说明世界该漂移了，
     *       不该让玩家正在挖的那一块变成"按原版掉落"；</li>
     *   <li>拒绝转换的后果是<b>静默不回退</b>：玩家看着 A 挖下去，拿到的却是真实方块 B 的掉落，
     *       比"按看到的给"更糟；</li>
     *   <li>而且它触发得非常容易——调试时钟被拨到高速时（{@code /focaldecay period speed}），
     *       一次普通挖掘就能跨过好几个周期。</li>
     * </ul>
     * 所以周期现在只用于诊断，两条出口：{@link #performBreak} 在锁定跨周期时打一行 trace；
     * {@code /focaldecay mutation at} 把整份锁定（位置 / 维度 / 周期 / 目标）打出来。
     * 于是它不再是死数据，而且这两条出口回答的正是"为什么这次拿到的掉落和我预期的不一样"。
     * <p>
     * 区分 {@code NO_LOCK} 与 {@code STALE} 是有用的：前者什么都不用做，
     * 后者要把陈旧数据清掉（否则下一次挖掘会误用）。
     */
    public static LockVerdict validateLock(BreakData breakData, BlockPos pos,
                                           ResourceKey<Level> dimension) {
        if (!breakData.isActive() || breakData.getTargetState() == null) {
            return LockVerdict.NO_LOCK;
        }
        if (!pos.equals(breakData.getPos()) || !dimension.equals(breakData.getDimension())) {
            return LockVerdict.STALE;
        }
        return LockVerdict.USABLE;
    }

}
