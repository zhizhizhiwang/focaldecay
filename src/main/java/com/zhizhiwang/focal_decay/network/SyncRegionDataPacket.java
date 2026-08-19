package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C：同步某维度的保护区域数据（稳定锚位置 + 保护半径 + 方块诞生周期）。
 * 设计大纲 §8.1 的 SyncRegionDataPacket 雏形，覆盖区域后续扩展。
 */
public record SyncRegionDataPacket(ResourceKey<Level> dimension, int anchorRadius, long[] anchors,
                                   long[] birthPositions, long[] birthPeriods)
        implements CustomPacketPayload {

    public static final Type<SyncRegionDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_region_data"));

    public static final StreamCodec<FriendlyByteBuf, SyncRegionDataPacket> STREAM_CODEC =
            StreamCodec.of(SyncRegionDataPacket::encode, SyncRegionDataPacket::new);

    public SyncRegionDataPacket(FriendlyByteBuf buf) {
        this(buf.readResourceKey(Registries.DIMENSION), buf.readVarInt(), buf.readLongArray(),
                buf.readLongArray(), buf.readLongArray());
    }

    private static void encode(FriendlyByteBuf buf, SyncRegionDataPacket packet) {
        buf.writeResourceKey(packet.dimension());
        buf.writeVarInt(packet.anchorRadius());
        buf.writeLongArray(packet.anchors());
        buf.writeLongArray(packet.birthPositions());
        buf.writeLongArray(packet.birthPeriods());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.applyRegionData(
                dimension, anchorRadius, anchors, birthPositions, birthPeriods));
    }
}
