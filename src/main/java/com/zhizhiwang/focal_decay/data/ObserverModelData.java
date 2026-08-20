package com.zhizhiwang.focal_decay.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 观测模型的训练数据（设计大纲 §3.3），存于物品 DataComponent（NeoForge 1.21.1 DataComponentType）。
 */
public record ObserverModelData(String type, List<String> trainedTargets, List<String> trainedEntities,
                                double stabilityStrength, int bioEnergy, boolean totalStability) {

    public static final String TYPE_BLANK = "blank";
    public static final String TYPE_TRAINING = "training";
    public static final String TYPE_SEMANTIC_LOCK = "semantic_lock";
    public static final String TYPE_GUIDED = "guided";
    public static final String TYPE_BIO = "bio_stabilizer";
    public static final String TYPE_TOTAL = "total_stability";

    public static final Codec<ObserverModelData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("type").forGetter(ObserverModelData::type),
            Codec.STRING.listOf().optionalFieldOf("trainedTargets", List.of()).forGetter(ObserverModelData::trainedTargets),
            Codec.STRING.listOf().optionalFieldOf("trainedEntities", List.of()).forGetter(ObserverModelData::trainedEntities),
            Codec.DOUBLE.optionalFieldOf("stabilityStrength", 0.0).forGetter(ObserverModelData::stabilityStrength),
            Codec.INT.optionalFieldOf("bioEnergy", 0).forGetter(ObserverModelData::bioEnergy),
            Codec.BOOL.optionalFieldOf("totalStability", false).forGetter(ObserverModelData::totalStability)
    ).apply(inst, ObserverModelData::new));

    public static final StreamCodec<ByteBuf, ObserverModelData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ObserverModelData::type,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ObserverModelData::trainedTargets,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ObserverModelData::trainedEntities,
            ByteBufCodecs.DOUBLE, ObserverModelData::stabilityStrength,
            ByteBufCodecs.INT, ObserverModelData::bioEnergy,
            ByteBufCodecs.BOOL, ObserverModelData::totalStability,
            ObserverModelData::new);

    public static ObserverModelData blank() {
        return new ObserverModelData(TYPE_BLANK, List.of(), List.of(), 0.0, 0, false);
    }

    /** 生物稳定模型：无需训练，初始能量为 0，靠范围内生物生命值补充。 */
    public static ObserverModelData bio() {
        return new ObserverModelData(TYPE_BIO, List.of(), List.of(), 1.0, 0, false);
    }
}
