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

    /**
     * 单个原型机的效果摘要（位置 + 半径 + 模型类型与训练目标 + 生物稳定是否生效 + 引导概念/完备度 + 复制代数）。
     * <p>
     * {@code copies} 是必需的：候选观测者的"是否练满"取决于它（副本需要更多训练量），
     * 客户端要独立算出一致的保护判定，就必须知道代数。
     * <p>
     * {@code bioActive} 而不是能量数值（2026-09-17）：客户端只用它判"生物稳定是否硬保护"
     * （{@code bioEnergy > 0}），而能量每刻都在变。发数值的话"客户端能观察到的内容变了没有"
     * 就没法与包体做结构比较，增量同步会退化成每刻一包；发布尔值之后
     * {@code PrototypeData.equals} 恰好等于"客户端看到的东西变了没有"。
     */
    public record PrototypeData(long pos, int radius, String type,
                                List<String> trainedTargets, List<String> trainedEntities,
                                boolean bioActive, String concept, int progress, double q, int copies) {
        // 分量超过 composite 上限（6），手写编码
        public static final StreamCodec<ByteBuf, PrototypeData> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeLong(p.pos());
                    buf.writeInt(p.radius());
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.type());
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.trainedTargets());
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.trainedEntities());
                    buf.writeBoolean(p.bioActive());
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.concept());
                    buf.writeInt(p.progress());
                    buf.writeDouble(p.q());
                    buf.writeInt(p.copies());
                },
                buf -> new PrototypeData(
                        buf.readLong(), buf.readInt(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                        buf.readBoolean(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readInt(),
                        buf.readDouble(),
                        buf.readInt()));
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
