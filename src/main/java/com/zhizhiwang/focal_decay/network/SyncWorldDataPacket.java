package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** S→C：同步全局末日天数与观测者在线状态（阶段判定 + 失焦终止门控）。 */
public record SyncWorldDataPacket(long days, boolean observerOnline) implements CustomPacketPayload {

    public static final Type<SyncWorldDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_world_data"));

    public static final StreamCodec<FriendlyByteBuf, SyncWorldDataPacket> STREAM_CODEC =
            StreamCodec.of(SyncWorldDataPacket::encode, SyncWorldDataPacket::new);

    public SyncWorldDataPacket(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readBoolean());
    }

    private static void encode(FriendlyByteBuf buf, SyncWorldDataPacket packet) {
        buf.writeLong(packet.days());
        buf.writeBoolean(packet.observerOnline());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.setWorldData(days, observerOnline));
    }
}
