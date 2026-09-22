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
     * 单个原型机的效果摘要（位置 + 半径 + 模型类型与训练目标 + 生物稳定是否生效 + 引导概念/完备度 +
     * 候选体是否练满）。
     * <p>
     * <b>发"是否练满"而不是"进度 + 复制代数"</b>（2026-09-17）：完成线取决于训练增益表
     * （原件 100 点、一代副本 143 点、二代副本 200 点——见 {@code ObserverModelData#candidateGain}），
     * 让客户端拿两个原始值自己复算，就等于把一条配置公式同步到两端；一旦两端配置不同，
     * 就会"客户端以为有硬保护、服务端照样转换"。服务端算结论、客户端照用，这一条不可能对不上。
     * <p>
     * {@code bioActive} 而不是能量数值（同为 2026-09-17）：客户端只用它判"生物稳定是否硬保护"
     * （{@code bioEnergy > 0}），而能量每刻都在变。发数值的话"客户端能观察到的内容变了没有"
     * 就没法与包体做结构比较，增量同步会退化成每刻一包；发布尔值之后
     * {@code PrototypeData.equals} 恰好等于"客户端看到的东西变了没有"。
     */
    public record PrototypeData(long pos, int radius, String type,
                                List<String> trainedTargets, List<String> trainedEntities,
                                boolean bioActive, String concept, boolean candidateComplete, double q) {
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
                    buf.writeBoolean(p.candidateComplete());
                    buf.writeDouble(p.q());
                },
                buf -> new PrototypeData(
                        buf.readLong(), buf.readInt(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                        buf.readBoolean(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(),
                        buf.readDouble()));
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
