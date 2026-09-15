package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C：同步"世界级时钟状态"——末日天数、观测者在线状态、调试时钟（倍率 / 偏移）。
 * <p>
 * 三样东西放同一个包，是因为它们满足同一个条件：<b>服务端与客户端必须逐位一致</b>。
 * 前两者决定阶段与失焦终止，后者决定"当前失焦刻"；任何一项两端不同，失焦预览就会和
 * 真实转换对不上（客户端显示 A、服务端给 B）。
 * <p>
 * 调试时钟是 {@code /focaldecay period} 拨动的那根指针，不落盘（见 {@code FocalDecayWorldData}）。
 */
public record SyncWorldDataPacket(long days, boolean observerOnline, double clockSpeed, long clockOffset)
        implements CustomPacketPayload {

    public static final Type<SyncWorldDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_world_data"));

    public static final StreamCodec<FriendlyByteBuf, SyncWorldDataPacket> STREAM_CODEC =
            StreamCodec.of(SyncWorldDataPacket::encode, SyncWorldDataPacket::new);

    public SyncWorldDataPacket(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readBoolean(), buf.readDouble(), buf.readLong());
    }

    private static void encode(FriendlyByteBuf buf, SyncWorldDataPacket packet) {
        buf.writeLong(packet.days());
        buf.writeBoolean(packet.observerOnline());
        buf.writeDouble(packet.clockSpeed());
        buf.writeLong(packet.clockOffset());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.setWorldData(
                days, observerOnline, clockSpeed, clockOffset));
    }
}
