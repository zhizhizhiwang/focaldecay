package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S：客户端请求重发"当前维度的区域数据 + 世界数据"（2026-09-25，BACKLOG `P0-5`）。
 * <p>
 * <b>为什么需要它</b>：区域镜像（原型机保护范围 + 方块诞生周期）由整表快照建立，
 * 而快照只在<b>登录</b>与<b>换维度</b>时下发（{@code MutationEventHandler}）。
 * 但客户端丢弃镜像的条件是"{@code ClientLevel} 实例被替换"，这两件事并不重合——
 * <b>同维度死亡重生</b>就会换一个 {@code ClientLevel}：镜像被清掉，却没有任何重发钩子。
 * 后果是整局游戏里客户端再也不知道哪片区域受保护、哪些方块是玩家放置的：
 * 在被保护的立方体里照画幽灵（服务端根本不会转换它），玩家放置的方块被当成世界原生方块。
 * <p>
 * <b>为什么是客户端主动请求，而不是服务端在重生事件里直接推</b>：两者都需要，但请求这条更根本——
 * 它把判据放在"客户端发现自己的数据没了"这个<b>事实</b>上，而不是"服务端认为客户端应该需要数据了"这个
 * <b>推断</b>上。任何导致客户端丢镜像的原因（重生、跨维度、将来的新机制）都被同一条逻辑覆盖。
 * 服务端侧在 {@code PlayerEvent.PlayerRespawnEvent} 里也补了一次延后推送，作为不依赖客户端配合的兜底。
 * <p>
 * 无字段：请求就是"请给我当前状态"。服务端只回给请求者，不回给全维度。
 */
public record RequestRegionDataPacket() implements CustomPacketPayload {

    public static final Type<RequestRegionDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "request_region_data"));

    public static final StreamCodec<FriendlyByteBuf, RequestRegionDataPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestRegionDataPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                // 只补这两样：输入快照（种子/配置）不随玩家行为变化，登录时发过就够。
                ModNetwork.sendRegionData(player);
                ModNetwork.sendWorldData(player);
            }
        });
    }
}
