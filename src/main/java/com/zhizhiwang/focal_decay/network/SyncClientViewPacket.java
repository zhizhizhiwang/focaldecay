package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.mutation.InteractionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C→S：客户端回报"<b>我这一眼看到的是哪一个失焦刻</b>"（2026-09-17）。
 * <p>
 * <b>为什么需要它</b>：失焦刻是 {@code gameTick / base_interval}，而客户端的
 * {@code level.getGameTime()} 是<b>本地自走</b>的计数器——服务端只在
 * {@code MinecraftServer#tickChildren} 里每 20 tick 用 {@code ClientboundSetTimePacket} 校一次
 * （见 {@code ClientLevel#tickTime}：{@code setGameTime(levelData.getGameTime() + 1)}）。
 * 两边都跑满 20 TPS 时客户端落后半个 RTT（局域网 ≤1 刻），可**服务端掉帧时客户端会跑到前面**，
 * 两次校准之间最多领先 20 刻。
 * <p>
 * 那个窗口里客户端显示的是周期 N、服务端解析用的是 N+1：玩家挖掉自己看到的东西，
 * 服务端却按另一个周期算目标，掉落自然对不上。修法只有一条——让服务端知道
 * "客户端的指针指在哪一刻"，也就是这个包。
 * <p>
 * <b>服务端不盲信</b>（{@link InteractionHandler#periodWithinSkew}）：回报只在
 * "位置对得上 + 足够新 + 偏差在物理可能的范围内"时被采用，否则退回服务端自己的显示刻。
 * 位置是必须的：它把回报绑定到这一次交互，避免一条陈旧回报影响别处的解析。
 * <p>
 * 回报的是<b>周期</b>而不是客户端的 gameTick：周期就是客户端实际用来解析的那个值，
 * 由服务端换算反而会引入"客户端的 speed/offset 是否已更新"这类额外假设。
 */
public record SyncClientViewPacket(long packedPos, long period) implements CustomPacketPayload {

    public static final Type<SyncClientViewPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_client_view"));

    public static final StreamCodec<FriendlyByteBuf, SyncClientViewPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SyncClientViewPacket::packedPos,
            ByteBufCodecs.VAR_LONG, SyncClientViewPacket::period,
            SyncClientViewPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                InteractionHandler.recordClientView(player, packedPos, period);
            }
        });
    }
}
