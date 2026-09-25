package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 配置重载的处理（2026-09-30，BACKLOG `P0-7`）。
 * <p>
 * <b>为什么单独一个类、注册在 mod bus 上</b>：{@link ModConfigEvent} 属于
 * {@code IModBusEvent}，只能挂在 <b>mod 事件总线</b>上；而 {@code MutationEventHandler} 是注册在
 * 公共 NeoForge 总线上的（它处理的是游戏事件）。混在一起会在<b>模组构造阶段</b>直接崩：
 * <pre>
 *   Method ... has @SubscribeEvent annotation, but takes an argument that is not valid for this bus
 *   Caused by: IModBusEvent events are not allowed on the common NeoForge bus! Use a mod bus instead.
 * </pre>
 * 这个错误发生在构造期，所以整个模组加载失败——不是"这个功能不生效"那种温和的失败。
 * <p>
 * <b>为什么必须重发快照</b>：客户端预览<b>只</b>吃同步下来的那份，而快照原先只在登录/换维度时发。
 * 运行期改了 SERVER 配置之后，服务端立刻按新配置解析，客户端却还按旧概率画幽灵——
 * 又是一个"两端算的不是一个世界"。这类不一致的代价很高（玩家挖下去掉落对不上），
 * 而修法只是重发一个包。
 * <p>
 * 只处理 SERVER 配置：决定解析输入的每一项都在 SERVER 里。COMMON/CLIENT 不影响两端一致性
 * （原型机半径、后处理强度这些本来就是各端自己的事，服务端会把算好的结果随区域数据下发）。
 */
public final class ModConfigHandler {

    private ModConfigHandler() {
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() != ModConfig.Type.SERVER) {
            return;
        }
        // 1) 丢弃服务端权威快照缓存（下次访问按新配置重建）。
        MutationSettings.invalidateServerCache();

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return; // 无服务端时重载（主菜单 / 数据生成）：没有客户端要通知
        }
        // 2) 立刻重建一次：fromConfig 会顺带把 wild_auto_include 复位，并在取值变化时丢弃突变索引缓存
        //    （这个开关决定大池成员，因而决定每一个目标，不能留在旧值上）。
        MutationSettings.server(server.overworld().getSeed());

        // 3) 把新快照重发给所有在线玩家。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModNetwork.sendMutationSettings(player);
        }
        FocalDecay.LOGGER.info("[focal_decay] server config reloaded - mutation settings re-sent to {} player(s)",
                server.getPlayerList().getPlayers().size());
    }
}
