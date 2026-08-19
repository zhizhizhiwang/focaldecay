package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** S→C：同步全局末日天数，客户端据此判定阶段（周期/概率/影响范围）。 */
public record SyncWorldDataPacket(long days) implements CustomPacketPayload {

    public static final Type<SyncWorldDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_world_data"));

    public static final StreamCodec<FriendlyByteBuf, SyncWorldDataPacket> STREAM_CODEC =
            StreamCodec.of(SyncWorldDataPacket::encode, SyncWorldDataPacket::new);

    public SyncWorldDataPacket(FriendlyByteBuf buf) {
        this(buf.readLong());
    }

    private static void encode(FriendlyByteBuf buf, SyncWorldDataPacket packet) {
        buf.writeLong(packet.days());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.setWorldDays(days));
    }
}
