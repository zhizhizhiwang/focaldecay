package com.zhizhiwang.focal_decay.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.GuideAdvancementHandler;
import com.zhizhiwang.focal_decay.mutation.InteractionHandler;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationPoolManager;
import com.zhizhiwang.focal_decay.mutation.MutationTargets;
import com.zhizhiwang.focal_decay.mutation.pool.MutationAudit;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 测试命令：
 *  - /focaldecay days          查询当前末日天数与阶段
 *  - /focaldecay days <n>      手动设定天数（权限 2），广播给所有玩家
 *  - /focaldecay throne        查询本世界末地王座的生成位置（调试用）
 *  - /focaldecay unlock        解开手册《王座协议》《重聚焦》两卷的位置锁（权限 2）
 */
public final class ModCommands {

    private ModCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("focaldecay")
                .then(Commands.literal("days")
                        .executes(ctx -> queryDays(ctx.getSource()))
                        .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> setDays(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("throne")
                        .executes(ctx -> queryThrone(ctx.getSource())))
                .then(Commands.literal("inspect")
                        .executes(ctx -> inspectHeld(ctx.getSource())))
                .then(Commands.literal("trace")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> setTrace(ctx.getSource(), true))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> setTrace(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("mutation")
                        .then(Commands.literal("audit")
                                .executes(ctx -> auditMutation(ctx.getSource())))
                        .then(Commands.literal("selftest")
                                .executes(ctx -> selfTestMutation(ctx.getSource())))
                        .then(Commands.literal("at")
                                .executes(ctx -> mutationAt(ctx.getSource()))))
                .then(Commands.literal("refocus")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> setRefocus(ctx.getSource(), true))
                        .then(Commands.argument("online", BoolArgumentType.bool())
                                .executes(ctx -> setRefocus(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "online")))))
                .then(Commands.literal("unlock")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> unlockManual(ctx.getSource()))));
    }

    /**
     * 把一行文本发给命令执行者；无玩家执行者（函数 / 控制台）时改为写服务器日志。
     * <p>
     * 必须这样做：mcfunction 里命令的输出是被吞掉的，验证脚本只能靠日志拿到结果，
     * 而玩家手动执行时显然应该看聊天栏。
     */
    private static void report(CommandSourceStack source, String line) {
        if (source.getPlayer() == null) {
            com.zhizhiwang.focal_decay.FocalDecay.LOGGER.info(line);
        }
        source.sendSuccess(() -> Component.literal(line), false);
    }

    /** 突变查表自检：对称性 / 无吸收态 / 形态类闭合。 */
    private static int auditMutation(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        MutationIndex index = MutationIndexes.get(level.dimension());
        for (String line : MutationAudit.audit(index)) {
            report(source, line);
        }
        return 1;
    }

    /** 运行期自测：确定性 / 不收敛 / 对称 / 状态迁移 / 形态类门控。 */
    private static int selfTestMutation(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        for (String line : MutationAudit.selfTest(level, pos)) {
            report(source, line);
        }
        return 1;
    }

    /**
     * 打印执行者所在位置向上一格的真实方块、形态类、所属池的候选数量与"当前可见目标"。
     * 排查"这个方块为什么不变 / 为什么变成了那个"时最直接的一条指令。
     */
    private static int mutationAt(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition()).below();
        MutationIndex index = MutationIndexes.get(level.dimension());
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        int shapeClass = index.shapeClass(block);
        BlockState target = MutationTargets.resolveServer(level, pos, state);
        report(source, "[mutation] " + pos.toShortString()
                + " block=" + BuiltInRegistries.BLOCK.getKey(block)
                + " shapeClass=" + index.shapeClasses().name(shapeClass)
                + " source=" + index.isSource(block)
                + " localCandidates=" + index.localCount(block)
                + " wildInClass=" + index.wild().count(shapeClass));
        report(source, "  birthPeriod="
                + MutationPoolManager.get(level).getBlockBirthPeriod(pos)
                + " target=" + BuiltInRegistries.BLOCK.getKey(target.getBlock())
                + " targetState=" + target);
        return 1;
    }

    /**
     * 强制翻转"重聚焦"状态（观测者是否在线）。
     * <p>
     * 存在的理由：正常流程要练出一个候选观测者模型、装进核心、等 100 tick 才能到达这个状态，
     * 而重聚焦之后有一堆只在那一刻才生效的表现（客户端遮罩淡出、失焦预览清空、实体突变停止）需要反复看。
     * 走的是和核心激活完全相同的 {@code FocalDecayWorldData.setObserverOnline}，所以看到的就是真实行为。
     */
    private static int setRefocus(CommandSourceStack source, boolean online) {
        FocalDecayWorldData.get(source.getServer()).setObserverOnline(online);
        report(source, "Focal Decay observerOnline = " + online
                + (online ? " (client veil fades out, defocus preview cleared)"
                          : " (defocus resumes)"));
        return 1;
    }

    private static int setTrace(CommandSourceStack source, boolean enabled) {        InteractionHandler.traceEnabled = enabled;
        // 客户端渲染侧诊断：只有客户端才有意义，用反射避免在专用服务器上触碰客户端类
        try {
            Class.forName("com.zhizhiwang.focal_decay.client.ClientRenderDebug")
                    .getField("enabled").setBoolean(null, enabled);
        } catch (Throwable ignored) {
            // 专用服务器：没有客户端渲染，忽略
        }
        source.sendSuccess(() -> Component.translatable(
                enabled ? "message.focal_decay.trace_on" : "message.focal_decay.trace_off"), true);
        return 1;
    }

    /**
     * 打印手持物品的观测模型数据与全部数据组件。
     * <p>
     * 存在的理由：原版 {@code /data get entity} <b>只列原版内建组件</b>，mod 注册的组件
     * （如 {@code focal_decay:observer_model_data}）不会出现在那份清单里，
     * 于是"模型到底有没有数据、代数是多少"用原版手段根本查不到。这条指令补上这个盲区。
     */
    private static int inspectHeld(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.focal_decay.unlock_needs_player"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("message.focal_decay.inspect_empty"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§7—— " + held.getItem() + " ×" + held.getCount()
                + "  (" + BuiltInRegistries.ITEM.getKey(held.getItem()) + ")"), false);

        ObserverModelData data = ObserverModelItem.getData(held);
        if (data == null) {
            source.sendSuccess(() -> Component.literal("§cobserver_model_data: 无（该物品没有模型数据）"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§aobserver_model_data:"), false);
            source.sendSuccess(() -> Component.literal("  type=" + data.type()
                    + "  copies=" + data.copies()
                    + "  progress=" + data.progress()
                    + "  required=" + ObserverModelData.requiredCandidatePoints(data.copies())), false);
            source.sendSuccess(() -> Component.literal("  complete=" + data.candidateComplete()
                    + "  targets=" + data.trainedTargets().size()
                    + "  entities=" + data.trainedEntities().size()
                    + "  bioEnergy=" + data.bioEnergy()), false);
            source.sendSuccess(() -> Component.literal("  concept=" + data.concept()
                    + "  q=" + data.stabilityStrength()
                    + "  totalStability=" + data.totalStability()), false);
        }

        var components = held.getComponents();
        source.sendSuccess(() -> Component.literal("§7全部组件（共 " + components.size() + " 个）:"), false);
        for (var typed : components) {
            source.sendSuccess(() -> Component.literal("  · " + typed), false);
        }
        return 1;
    }

    /**
     * 解开手册两卷的位置锁（等价于"抵达末地王座"，供测试与误锁兜底）。
     * 只对玩家执行者有效——advancement 是挂在玩家身上的。
     */
    private static int unlockManual(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.focal_decay.unlock_needs_player"));
            return 0;
        }
        GuideAdvancementHandler.unlock(player);
        source.sendSuccess(() -> Component.translatable("message.focal_decay.unlock_done"), true);
        return 1;
    }

    private static int queryDays(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        long days = FocalDecayWorldData.get(server).getDays();
        int stage = MutationHelper.currentStage(days);
        source.sendSuccess(() -> Component.literal("Focal Decay days: " + days + " (stage " + stage + ")"), false);
        return (int) days;
    }

    private static int setDays(CommandSourceStack source, int days) {
        MinecraftServer server = source.getServer();
        FocalDecayWorldData data = FocalDecayWorldData.get(server);
        data.setDays(days);
        int stage = MutationHelper.currentStage(days);
        source.sendSuccess(() -> Component.literal("Focal Decay days set to " + days + " (stage " + stage + ")"), true);
        return 1;
    }

    private static int queryThrone(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        long seed = level.getSeed();
        BlockPos throne = ThroneStructure.thronePos(seed);
        ChunkPos chunk = ThroneStructure.throneChunk(seed);
        double distance = Math.hypot(throne.getX(), throne.getZ());
        source.sendSuccess(() -> Component.literal("End Throne at "
                + throne.toShortString() + " (chunk " + chunk.x + ", " + chunk.z
                + "), distance " + Math.round(distance) + " blocks from origin"), false);
        return 1;
    }
}
