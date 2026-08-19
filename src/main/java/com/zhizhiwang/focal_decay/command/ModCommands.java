package com.zhizhiwang.focal_decay.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 测试命令：
 *  - /focaldecay days          查询当前末日天数与阶段
 *  - /focaldecay days <n>      手动设定天数（权限 2），广播给所有玩家
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
                                .executes(ctx -> setDays(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days"))))));
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
}
