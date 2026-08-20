package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * S→C：同步某维度的区域数据（有效原型机 + 模型效果、方块诞生周期）。
 * 设计大纲 §8.1。
 */
public record SyncRegionDataPacket(ResourceKey<Level> dimension, List<PrototypeData> prototypes,
                                   long[] birthPositions, long[] birthPeriods)
        implements CustomPacketPayload {

    /** 单个原型机的效果摘要（位置 + 半径 + 模型类型与训练目标 + 生物稳定能量）。 */
    public record PrototypeData(long pos, int radius, String type,
                                List<String> trainedTargets, List<String> trainedEntities, int bioEnergy) {
        public static final StreamCodec<ByteBuf, PrototypeData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, PrototypeData::pos,
                ByteBufCodecs.VAR_INT, PrototypeData::radius,
                ByteBufCodecs.STRING_UTF8, PrototypeData::type,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), PrototypeData::trainedTargets,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), PrototypeData::trainedEntities,
                ByteBufCodecs.VAR_INT, PrototypeData::bioEnergy,
                PrototypeData::new);
    }

    public static final Type<SyncRegionDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_region_data"));

    public static final StreamCodec<FriendlyByteBuf, SyncRegionDataPacket> STREAM_CODEC = StreamCodec.composite(
            StreamCodec.of((buf, key) -> buf.writeResourceKey(key), buf -> buf.readResourceKey(Registries.DIMENSION)),
            SyncRegionDataPacket::dimension,
            PrototypeData.STREAM_CODEC.apply(ByteBufCodecs.list()),
            SyncRegionDataPacket::prototypes,
            StreamCodec.of((buf, arr) -> buf.writeLongArray(arr), FriendlyByteBuf::readLongArray),
            SyncRegionDataPacket::birthPositions,
            StreamCodec.of((buf, arr) -> buf.writeLongArray(arr), FriendlyByteBuf::readLongArray),
            SyncRegionDataPacket::birthPeriods,
            SyncRegionDataPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.applyRegionData(
                dimension, prototypes, birthPositions, birthPeriods));
    }
}
