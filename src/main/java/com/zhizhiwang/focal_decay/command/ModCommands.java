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
                .then(Commands.literal("unlock")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> unlockManual(ctx.getSource()))));
    }

    private static int setTrace(CommandSourceStack source, boolean enabled) {
        InteractionHandler.traceEnabled = enabled;
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
