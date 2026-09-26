package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.ObserverCoreBlock;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import com.zhizhiwang.focal_decay.network.ThroneRitualPacket;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 末地王座仪式（设计大纲 §3.5.1）：
 *  - <b>触发</b>（2026-09-17 修正）：手持<b>未激活的 OBSR-EX</b>，右键一座<b>已放置</b>在王座附近的
 *    观测者基座——把 EX 放进基座的同时开始仪式。以前是"右键王座区域"触发，且允许基座只带在包里，
 *    于是"拿着基座右键王座"也能开（那不是设计意图）；
 *  - <b>维持</b>：期间按配置波次生成强敌，玩家离开半径按配置暂停或失败；
 *    <b>那座基座与其中的 EX 必须一直在原位</b>——取走模型（丢出去/塞进箱子）或拆掉基座都算现场条件失效，
 *    立即中断且不给奖励（模型随基座掉落，可以重来）；
 *  - <b>完成</b>：基座里那枚未激活模型被替换为已激活版本（完全稳定锚就地升级），
 *    广播 ThroneRitualPacket、粒子与音效；
 *  - 常驻粒子：王座周围周期性漂浮末地棒/传送门粒子。
 */
public final class ThroneRitualHandler {
    private static final int TICKS_PER_SECOND = 20;
    private static final int AMBIENT_PARTICLE_INTERVAL = 40;

    private ThroneRitualHandler() {
    }

    // ------------------------------------------------------------------
    // 触发
    // ------------------------------------------------------------------

    /**
     * 仪式入口：<b>手持未激活的 OBSR-EX 右键已放置的观测者基座</b>。
     * <p>
     * 手里不是未激活 EX 时直接返回，让事件照常走原版流程（右键基座打开它的 GUI，
     * 那是正常装/取模型的途径），所以这里不提示、也不取消。
     */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.END) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            return; // 只认观测者基座本身：右键王座/别处都不该启动仪式
        }
        if (!(level.getBlockEntity(pos) instanceof AnchorPrototypeBlockEntity prototype)) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!held.is(ModItems.TOTAL_STABILITY_MODEL.get())) {
            return; // 手里不是未激活的 OBSR-EX：交给原版（打开基座 GUI）
        }

        BlockPos throne = ThroneStructure.thronePos(level.getSeed());
        int radius = FocalDecayConfig.THRONE_RITUAL_RADIUS.get();
        if (pos.distSqr(throne) > (double) radius * radius) {
            // 基座必须在王座附近：仪式要求"原型机与王座连接"（§3.5.1），而且波次与维持判定都在王座处
            player.displayClientMessage(
                    Component.translatable("message.focal_decay.ritual_need_prototype"), true);
            return;
        }

        ThroneRitualData data = ThroneRitualData.get(level);
        if (data.isActive()) {
            player.displayClientMessage(Component.translatable("message.focal_decay.ritual_active"), true);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        // 基座插槽：空的就把手里的 EX 放进去（这是仪式的"献祭"），已经有未激活 EX 就直接用它
        // （暂停后回来续仪走这条），占用成别的东西则要求先取出，免得覆盖玩家自己的模型。
        ItemStack inSlot = prototype.getModelStack();
        if (inSlot.isEmpty()) {
            prototype.setItem(0, held.copyWithCount(1));
            held.shrink(1);
        } else if (!inSlot.is(ModItems.TOTAL_STABILITY_MODEL.get())) {
            player.displayClientMessage(
                    Component.translatable("message.focal_decay.ritual_slot_occupied"), true);
            return;
        }

        int totalTicks = FocalDecayConfig.THRONE_RITUAL_SECONDS.get() * TICKS_PER_SECOND;
        int waveInterval = FocalDecayConfig.THRONE_RITUAL_WAVE_INTERVAL_SECONDS.get() * TICKS_PER_SECOND;
        data.start(throne.asLong(), pos.asLong(), player.getUUID(), totalTicks, waveInterval);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS); // 别让基座 GUI 同时打开

        level.sendParticles(ParticleTypes.PORTAL,
                throne.getX() + 0.5, throne.getY() + 2.0, throne.getZ() + 0.5,
                200, 8, 4, 8, 0.5);
        level.sendParticles(ParticleTypes.END_ROD,
                throne.getX() + 0.5, throne.getY() + 6.0, throne.getZ() + 0.5,
                120, 10, 6, 10, 0.4);
        level.playSound(null, throne, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 1.0F, 1.0F);
        level.playSound(null, throne, SoundEvents.CONDUIT_ACTIVATE, SoundSource.AMBIENT, 1.0F, 1.0F);
        ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                ThroneRitualPacket.STATE_STARTED, data.remainingTicks(), data.totalTicks(), data.wave()));
    }

    // ------------------------------------------------------------------
    // 计时 / 波次 / 完成
    // ------------------------------------------------------------------
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension() != Level.END) {
                continue;
            }
            if (!level.players().isEmpty()
                    && level.getGameTime() % AMBIENT_PARTICLE_INTERVAL == 0) {
                BlockPos throne = ThroneStructure.thronePos(level.getSeed());
                if (level.isLoaded(throne)) {
                    spawnAmbientParticles(level, throne);
                    spawnCoreAmbientParticles(level, throne);
                }
            }
            ThroneRitualData data = ThroneRitualData.get(level);
            if (data.isActive()) {
                tickRitual(level, data);
            }
        }
    }

    /**
     * 仪式这一 tick 该做什么（<b>纯函数</b>，供自测）。
     * <p>
     * 抽出来是因为原先的判断散在三处（在线性 / 存活 / 是否在半径内），而其中<b>一处漏了</b>
     * `pause_on_leave`，造成的是<b>数据销毁</b>级后果：
     * <ul>
     *   <li>玩家<b>离开半径</b> → 受 {@code pause_on_leave} 保护（保留了 {@code playerId}）；</li>
     *   <li>玩家<b>离线</b>（退出世界 / 断线 / 服务器刚启动还没登录）→ <b>无条件</b> {@code fail}，
     *       而 {@code fail} → {@code stop()} 会清空 {@code playerId}。</li>
     * </ul>
     * 于是"玩家一退出世界，仪式进度就被销毁并存盘"，而且<b>崩溃与正常退出表现完全一样</b>
     * ——因为它们走同一条分支。
     * <p>
     * 实测证据（作者 2026-09-26 提供的三个存档，我解了 NBT）：
     * <pre>
     *   新的世界 (7)  Active=0  Player=380df991-…  Remaining=130   ← pause 路径，留了 UUID
     *   新的世界 (4)  Active=0  Player 字段缺失     Remaining=522   ← stop 路径
     *   新的世界 (8)  Active=0  Player 字段缺失     Remaining=501   ← stop 路径（刚复现的那次）
     * </pre>
     * 只有 {@code stop()} 会清空 playerId，所以"Player 字段缺失"就是"被销毁过"的指纹。
     *
     * @param alive      玩家是否在线且存活；{@code null} = 不在线
     * @param inRadius   是否在仪式半径内
     * @param pauseOnLeave 配置 {@code throne_ritual_pause_on_leave}
     */
    public enum RitualAction { CONTINUE, PAUSE, FAIL }

    /**
     * @param alive       {@code null} = <b>不在线</b>（退出世界 / 断线）；
     *                    {@code true} = 在线且存活；{@code false} = <b>已死亡</b>
     * @param inRadius    是否在仪式半径内（离线时无意义）
     * @param pauseOnLeave 配置 {@code throne_ritual_pause_on_leave}
     *                    <p>
     *                    <b>注意"离线"与"死亡"是两件事，不要合并</b>：
     *                    离线是玩家关掉了游戏（应当保住进度），死亡是他真的没撑住（算失败）。
     *                    第一版写这条断言时我把两者混成一个参数，结果两个期望直接矛盾——
     *                    那正是这条断言现在专门检查的点。
     */
    public static RitualAction decide(Boolean alive, boolean inRadius, boolean pauseOnLeave) {
        if (alive == null) {
            // 离线：按 pause_on_leave 处理，与"离开半径"完全一致。
            // 离线<b>不</b>该销毁进度——玩家只是关掉了游戏，而 stop() 会清空 playerId。
            return pauseOnLeave ? RitualAction.PAUSE : RitualAction.FAIL;
        }
        if (!alive) {
            return RitualAction.FAIL; // 死亡：算失败（与离线区别对待）
        }
        return inRadius ? RitualAction.CONTINUE : (pauseOnLeave ? RitualAction.PAUSE : RitualAction.FAIL);
    }

    private static void tickRitual(ServerLevel level, ThroneRitualData data) {
        ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(data.playerId());
        BlockPos throne = BlockPos.of(data.thronePos());
        if (player == null) {
            // 玩家不在线（退出世界 / 断线 / 服务器刚启动还没登录）。
            //
            // ⚠️ 这里原先无条件 fail(level, data, null)，而 fail 会 stop() —— 那会**清空 playerId**
            // 并置 active=false。后果是**玩家一退出世界，仪式进度就被销毁并存盘**，
            // 而且崩溃与正常退出表现完全相同（走同一条分支）。详见 decide 的说明。
            //
            // 不发包：此刻没有玩家在线可收（sendToAllPlayers 遍历的是在线列表），
            // 玩家回来时 start() 会重新广播一次开始包。
            if (RitualAction.PAUSE == decide(null, true, FocalDecayConfig.THRONE_RITUAL_PAUSE_ON_LEAVE.get())) {
                data.pause();
            } else {
                fail(level, data, null);
            }
            return;
        }
        if (!player.isAlive()) {
            // 死亡：保持原行为（算失败）。与"离线"不同——离线是玩家关掉了游戏，
            // 死亡是他真的没撑住。
            fail(level, data, null);
            return;
        }
        // 现场条件先判：基座还在、里面还是那枚未激活的 EX。放在倒计时之前，
        // 这样"恰好在这一刻拆掉基座/取走模型"不会被算成完成。
        if (!prototypeIntact(level, data)) {
            fail(level, data, "message.focal_decay.ritual_lost_prototype");
            return;
        }
        int radius = FocalDecayConfig.THRONE_RITUAL_RADIUS.get();
        boolean inRadius = player.blockPosition().distSqr(throne) <= (double) radius * radius;
        if (!inRadius) {
            if (RitualAction.PAUSE == decide(true, false, FocalDecayConfig.THRONE_RITUAL_PAUSE_ON_LEAVE.get())) {
                data.pause();
                ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                        ThroneRitualPacket.STATE_PAUSED, data.remainingTicks(), data.totalTicks(), data.wave()));
            } else {
                fail(level, data, null);
            }
            return;
        }

        data.setRemainingTicks(data.remainingTicks() - 1);
        if (data.remainingTicks() <= 0) {
            complete(level, data);
            return;
        }
        data.setNextWaveTicks(data.nextWaveTicks() - 1);
        if (data.nextWaveTicks() <= 0) {
            data.setWave(data.wave() + 1);
            data.setNextWaveTicks(FocalDecayConfig.THRONE_RITUAL_WAVE_INTERVAL_SECONDS.get() * TICKS_PER_SECOND);
            spawnWave(level, throne, data.wave());
            level.sendParticles(ParticleTypes.DRAGON_BREATH,
                    throne.getX() + 0.5, throne.getY() + 3.0, throne.getZ() + 0.5,
                    60, 8, 3, 8, 0.2);
            level.playSound(null, throne, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.AMBIENT, 1.0F, 1.0F);
            ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                    ThroneRitualPacket.STATE_WAVE, data.remainingTicks(), data.totalTicks(), data.wave()));
        }
        if (level.getGameTime() % TICKS_PER_SECOND == 0) {
            level.sendParticles(ParticleTypes.PORTAL,
                    throne.getX() + 0.5, throne.getY() + 4.0, throne.getZ() + 0.5,
                    40, 8, 4, 8, 0.3);
            level.sendParticles(ParticleTypes.END_ROD,
                    throne.getX() + 0.5, throne.getY() + 5.0, throne.getZ() + 0.5,
                    12, 8, 4, 8, 0.15);
            ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                    ThroneRitualPacket.STATE_PROGRESS, data.remainingTicks(), data.totalTicks(), data.wave()));
        }
        data.setDirty();
    }

    private static void complete(ServerLevel level, ThroneRitualData data) {
        ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(data.playerId());
        BlockPos throne = BlockPos.of(data.thronePos());

        // 现场条件已由 tickRitual 保证：那座基座还在，里面还是那枚未激活的 EX。
        // 结算就是把它<b>就地</b>换成已激活版本（手册 §登座仪式"基座内那枚被替换"），
        // 不再往玩家背包塞一枚新的——那会让"模型其实没参与仪式"也拿到奖励。
        AnchorPrototypeBlockEntity prototype = prototypeAt(level, data);
        if (prototype == null) {
            fail(level, data, "message.focal_decay.ritual_lost_prototype");
            return;
        }
        ItemStack activated = new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get());
        // 仪式产出的是一枚全新的已激活模型，代数从 0 起算——即"原件"。
        // 这也意味着复制只能在激活之后进行（未激活的 EX 不在复制配方的可复制列表里）。
        ObserverModelItem.setData(activated, new ObserverModelData(
                ObserverModelData.TYPE_TOTAL, List.of(), List.of(), 1.0, "", 0, 0, true, 0));
        prototype.setItem(0, activated);

        level.sendParticles(ParticleTypes.END_ROD,
                throne.getX() + 0.5, throne.getY() + 4.0, throne.getZ() + 0.5,
                400, 10, 6, 10, 0.3);
        level.sendParticles(ParticleTypes.PORTAL,
                throne.getX() + 0.5, throne.getY() + 6.0, throne.getZ() + 0.5,
                300, 12, 8, 12, 0.4);
        level.playSound(null, throne, SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 1.0F, 1.0F);
        level.playSound(null, throne, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 1.0F, 1.0F);
        ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                ThroneRitualPacket.STATE_COMPLETE, 0, 0, 0));
        // 语义碎片里程碑：首次完成王座仪式（42ms）
        if (player != null) {
            FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
            if (worldData.grantFragmentOnce(FocalDecayWorldData.BIT_FRAGMENT_42MS)) {
                FragmentGrants.grant(player, ModItems.FRAGMENT_42MS.get());
            }
        }
        data.stop();
    }

    private static void fail(ServerLevel level, ThroneRitualData data, String reasonKey) {
        if (reasonKey != null && level.getPlayerByUUID(data.playerId()) instanceof ServerPlayer player) {
            // 原因只发给执行者（"为什么失败"对他有用，对其他人是噪音）
            player.displayClientMessage(Component.translatable(reasonKey), true);
        }
        ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                ThroneRitualPacket.STATE_FAILED, 0, 0, 0));
        data.stop();
    }

    private static void spawnWave(ServerLevel level, BlockPos throne, int wave) {
        List<? extends String> ids = FocalDecayConfig.THRONE_RITUAL_WAVE_ENTITIES.get();
        int size = FocalDecayConfig.THRONE_RITUAL_WAVE_SIZE.get();
        if (ids.isEmpty() || size <= 0) {
            return;
        }
        RandomSource random = level.getRandom();
        for (int i = 0; i < size; i++) {
            String id = ids.get(random.nextInt(ids.size()));
            try {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
                Entity entity = type == null ? null : type.create(level);
                if (entity == null) {
                    continue;
                }
                int dx = random.nextInt(13) - 6;
                int dz = random.nextInt(13) - 6;
                entity.setPos(throne.getX() + dx + 0.5, throne.getY() + 2.0, throne.getZ() + dz + 0.5);
                level.addFreshEntity(entity);
            } catch (Exception ignored) {
                // 非法实体 ID 忽略
            }
        }
    }

    // ------------------------------------------------------------------
    // 条件与辅助
    // ------------------------------------------------------------------
    private static void spawnAmbientParticles(ServerLevel level, BlockPos throne) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < 6; i++) {
            double x = throne.getX() + (random.nextDouble() - 0.5) * 14;
            double z = throne.getZ() + (random.nextDouble() - 0.5) * 14;
            double y = throne.getY() + 2.0 + random.nextDouble() * 16;
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0.05, 0, 0);
        }
        level.sendParticles(ParticleTypes.PORTAL,
                throne.getX() + 0.5, throne.getY() + 3.0, throne.getZ() + 0.5,
                8, 6, 3, 6, 0.1);
    }

    /** 彩蛋：王座中央的观测者核心激活后，周围漂浮蓝色光点。 */
    private static void spawnCoreAmbientParticles(ServerLevel level, BlockPos throne) {
        BlockPos corePos = throne.offset(0, 1, 0);
        if (!level.getBlockState(corePos).is(ModBlocks.OBSERVER_CORE.get())
                || !level.getBlockState(corePos).getValue(ObserverCoreBlock.POWERED)) {
            return;
        }
        RandomSource random = level.getRandom();
        for (int i = 0; i < 5; i++) {
            double x = corePos.getX() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
            double y = corePos.getY() + 0.5 + random.nextDouble() * 2.5;
            double z = corePos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 2.0;
            level.sendParticles(ParticleTypes.GLOW, x, y, z, 1, 0, 0.04, 0, 0);
        }
    }

    /**
     * 仪式的现场条件：<b>那座基座还在原位，而且里面还是那枚未激活的 OBSR-EX</b>。
     * <p>
     * 这一条同时覆盖了玩家能做的两件破坏：把模型取出来丢掉/塞进箱子（槽位空了或换成别的），
     * 以及拆掉基座（方块没了，模型会照常掉落）。两种情况都必须<b>立刻中断且不给奖励</b>——
     * 否则"仪式"就退化成了"点一下然后等 30 秒"。
     */
    private static boolean prototypeIntact(ServerLevel level, ThroneRitualData data) {
        return prototypeIntact(level, data.prototypePos());
    }

    /** 现场条件（按位置判定，便于自测直接喂坐标）。 */
    private static boolean prototypeIntact(ServerLevel level, long packedPos) {
        AnchorPrototypeBlockEntity prototype = prototypeAt(level, packedPos);
        return prototype != null && prototype.getModelStack().is(ModItems.TOTAL_STABILITY_MODEL.get());
    }

    /** 取出仪式绑定的那座基座；位置没记录、区块没加载或方块已被拆掉时返回 null。 */
    private static AnchorPrototypeBlockEntity prototypeAt(ServerLevel level, ThroneRitualData data) {
        return prototypeAt(level, data.prototypePos());
    }

    private static AnchorPrototypeBlockEntity prototypeAt(ServerLevel level, long packedPos) {
        if (packedPos == Long.MIN_VALUE) {
            return null; // 旧存档里没有这个字段：现场条件无从判定，按"不满足"处理
        }
        BlockPos pos = BlockPos.of(packedPos);
        if (!level.isLoaded(pos) || !level.getBlockState(pos).is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof AnchorPrototypeBlockEntity be ? be : null;
    }

    /**
     * 现场条件判定的自测（{@code /focaldecay throne selftest}）。
     * <p>
     * 在测试者头顶临时放一座基座，把四种情形走一遍：空基座 / 装了未激活 OBSR-EX / 装了别的模型 /
     * 基座被拆。这四条正是"维持"规则的全部输入——取走模型或拆掉基座必须判为不满足，
     * 否则仪式结束照样发奖励（2026-09-17 修的正是这个）。跑完把原地块还原，不留痕。
     */
    public static List<String> selfTest(ServerLevel level, BlockPos probePos) {
        List<String> out = new ArrayList<>();
        BlockPos probe = probePos.above();
        BlockState original = level.getBlockState(probe);
        level.setBlockAndUpdate(probe, ModBlocks.ANCHOR_PROTOTYPE.get().defaultBlockState());
        try {
            if (!(level.getBlockEntity(probe) instanceof AnchorPrototypeBlockEntity prototype)) {
                out.add("[ritual] probe base did not create a block entity  FAIL");
                return out;
            }
            boolean empty = !prototypeIntact(level, probe.asLong());
            prototype.setItem(0, new ItemStack(ModItems.TOTAL_STABILITY_MODEL.get()));
            boolean withEx = prototypeIntact(level, probe.asLong());
            prototype.setItem(0, new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get()));
            boolean wrongModel = !prototypeIntact(level, probe.asLong());
            level.removeBlock(probe, false);
            boolean removed = !prototypeIntact(level, probe.asLong());
            boolean ok = empty && withEx && wrongModel && removed;
            out.add("[ritual] site check (empty / inactive EX / other model / base removed): "
                    + (ok ? "PASS" : "FAIL empty=" + empty + " withEx=" + withEx
                    + " wrongModel=" + wrongModel + " removed=" + removed));
        } finally {
            // 还原：先拆掉临时基座（连带里面的模型），再放回原来的方块
            if (level.getBlockState(probe).is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
                level.removeBlock(probe, false);
            }
            level.setBlockAndUpdate(probe, original);
        }
        return out;
    }
}
