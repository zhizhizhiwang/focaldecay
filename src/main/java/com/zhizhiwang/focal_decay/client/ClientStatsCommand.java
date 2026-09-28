package com.zhizhiwang.focal_decay.client;

import com.mojang.brigadier.CommandDispatcher;
import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 客户端的诊断命令 {@code /focaldecay clientstats}（BACKLOG P1-5 / P0-6 的可观测点）。
 * <p>
 * <b>为什么单独一个类、而且是客户端专属</b>：
 * <ul>
 *   <li>它读的是 {@link ClientRenderCache}——一个 <b>客户端专属</b>的类。
 *       把它塞进服务端也加载的 {@code ModCommands} 里，专用服务器会在类校验阶段
 *       去找客户端类型，那是类加载失败，{@code Dist} 判断都来不及生效
 *       （同 {@code AGENTS.md} §3.5 那条可选依赖隔离的道理）；</li>
 *   <li>注册走 {@link RegisterClientCommandsEvent}，它在 <b>NeoForge 总线</b>上
 *       （不是 mod 总线），而且只在客户端触发。挂在 {@link ClientSetup} 上调用本类即可——
 *       {@code ClientSetup} 本身已经是 {@code @EventBusSubscriber(Dist.CLIENT)}，
 *       所以本类永远不会在专用服务器上被解析。</li>
 * </ul>
 * <p>
 * <b>为什么需要它</b>：{@code P1-5} 的验收标准原文是"给出某视距下每秒扫描位置数 /
 * 单次扫描耗时"——在那之前客户端扫描成本<b>完全不可观测</b>。{@code P0-6}（负缓存无界增长）
 * 也一样：原先只能靠周期边界那一行日志间接判断。这个命令把两个数字变成随时可查。
 * <p>
 * 埋点本身总是开着（几个 {@code LongAdder} 自增，量具比被测物便宜得多），
 * 但<b>不打任何日志</b>——要看就执行命令。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientStatsCommand {

    private ClientStatsCommand() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("focaldecay")
                .then(Commands.literal("clientstats")
                        // 权限：与其它 /focaldecay 诊断子命令保持一致（默认 2 级 / 管理员）。
                        // 客户端命令其实不经过服务端权限系统，但保持同一个门槛能让
                        // "谁能跑诊断"这件事在两边是同一个答案。
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> report(ctx.getSource(), false))
                        .then(Commands.literal("reset")
                                .executes(ctx -> report(ctx.getSource(), true)))));
    }

    /**
     * 无人值守冒烟：把与命令正文<b>同一段</b>格式化代码跑一遍并写日志，不经过命令系统。
     * <p>
     * 由 {@code ClientSetup#smokeClientStats} 在 {@code -Dfocaldecay.clientStatsSmoke=true}
     * 时于标题界面触发。存在的理由：命令注册本身由 Brigadier 兜底（失败会在启动日志报错），
     * 但"正文会不会抛异常"只有真的执行一次才知道，而真人执行这件事在无人值守时做不到。
     */
    public static void smokeReport() {
        // 幂等：TitleScreen 的 Init.Post 会触发不止一次（分辨率变化、返回主菜单都会再来一遍），
        // 第一版每次都打，日志里出现两份一模一样的行——诊断输出重复比不输出更容易误导。
        if (!smokeDone) {
            smokeDone = true;
            for (String line : format(ClientRenderCache.INSTANCE.scanStats())) {
                FocalDecay.LOGGER.info("{} (smoke, no world loaded yet - zeros are expected)", line);
            }
        }
    }

    private static boolean smokeDone;

    /** 命令与冒烟共用的格式化（放一处，免得两边的数字口径分叉）。 */
    private static String[] format(ClientRenderCache.ScanStats stats) {
        String nsPerPos = stats.nanosPerPosition() < 0
                ? "n/a" : String.format("%.1f", stats.nanosPerPosition());
        String cpuMs = stats.cpuNanos() < 0 ? "n/a" : String.format("%.1f", stats.cpuNanos() / 1e6);
        return new String[]{
                String.format(
                        "[clientstats] scannedSections=%d positions=%d resolves=%d (%.2f%% of positions)"
                                + " cpuMs=%s nsPerPosition=%s",
                        stats.sections(), stats.positions(), stats.resolves(),
                        stats.resolveShare() * 100.0, cpuMs, nsPerPos),
                String.format(
                        "[clientstats] ghostEntries=%d ghostSections=%d cachedDecisions=%d"
                                + " queuedSections=%d staleMidPeriod=%d",
                        stats.ghostEntries(), stats.ghostSections(), stats.cachedDecisions(),
                        stats.queuedSections(), stats.staleInvalidations()),
                // P1-5 的验收行：优化前 idleShare 恒为 0（队列一空就整体重建）。
                String.format(
                        "[clientstats] P1-5 idleShare=%.2f idleTicks=%d addedByView=%d rescannedByBlockChange=%d",
                        stats.idleShare(), stats.idleTicks(), stats.sectionsAdded(),
                        stats.sectionsRescanned()),
        };
    }

    private static int report(CommandSourceStack source, boolean resetFirst) {
        // reset 的语义是"把计时归零、然后立刻读一次"：读数是 0 反而没法确认命令通了，
        // 所以先复位再输出，玩家看到的是复位后的零值 + 缓存现状（缓存不归零）。
        if (resetFirst) {
            ClientRenderCache.INSTANCE.resetScanStats();
        }
        // 日志一律 ASCII：控制台可能是 GBK，中文会变乱码并掩盖线索。
        String[] lines = format(ClientRenderCache.INSTANCE.scanStats());
        String head = lines[0];
        String cache = lines[1];

        // 无玩家执行者（理论上客户端命令不会）仍然写日志，保证脚本能取到结论。
        if (source.getPlayer() == null) {
            FocalDecay.LOGGER.info(head);
            FocalDecay.LOGGER.info(cache);
        }
        source.sendSuccess(() -> Component.literal(head), false);
        source.sendSuccess(() -> Component.literal(cache), false);
        source.sendSuccess(() -> Component.literal(
                "[clientstats] cachedDecisions 是负缓存条目数：它在每个周期边界应当被清空，"
                        + "持续单调增长就是 BACKLOG P0-6 那类无界增长；"
                        + "queuedSections 是扫描队列深度，走完一圈的时间 = 深度 / (12 x 每秒扫描次数)。"), false);
        return 1;
    }
}
