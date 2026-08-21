package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** S→C：观测者核心激活完成，客户端播放全局粒子/音效与胜利提示。 */
public record ObserverCoreActivatePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ObserverCoreActivatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "observer_core_activate"));

    public static final StreamCodec<ByteBuf, ObserverCoreActivatePacket> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, ObserverCoreActivatePacket::pos,
                    ObserverCoreActivatePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.notifyCoreActivated(pos));
    }
}
