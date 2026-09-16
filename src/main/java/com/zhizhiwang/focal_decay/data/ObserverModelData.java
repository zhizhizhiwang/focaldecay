package com.zhizhiwang.focal_decay.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 观测模型的训练数据（设计大纲 §3.3），存于物品 DataComponent（NeoForge 1.21.1 DataComponentType）。
 *
 * @param copies 复制代数：0 = 原件，1/2 = 第几代副本。每复制一次损耗一代，半径递减（见
 *               {@code MutationPoolManager#radiusFor}）。OBSR-EX 的参数是硬件写死的，
 *               复制不是拷贝文件而是重铸，每次重铸都会丢失一部分分类精度。
 *               新增于 2026-09-11，用 {@code optionalFieldOf(..., 0)} 编码，旧存档的模型会自然读成原件。
 */
public record ObserverModelData(String type, List<String> trainedTargets, List<String> trainedEntities,
                                double stabilityStrength, String concept, int progress,
                                int bioEnergy, boolean totalStability, int copies) {

    public static final String TYPE_BLANK = "blank";
    public static final String TYPE_TRAINING = "training";
    public static final String TYPE_SEMANTIC_LOCK = "semantic_lock";
    public static final String TYPE_GUIDED = "guided";
    public static final String TYPE_BIO = "bio_stabilizer";
    public static final String TYPE_TOTAL = "total_stability";
    public static final String TYPE_CANDIDATE = "candidate";

    public static final Codec<ObserverModelData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("type").forGetter(ObserverModelData::type),
            Codec.STRING.listOf().optionalFieldOf("trainedTargets", List.of()).forGetter(ObserverModelData::trainedTargets),
            Codec.STRING.listOf().optionalFieldOf("trainedEntities", List.of()).forGetter(ObserverModelData::trainedEntities),
            Codec.DOUBLE.optionalFieldOf("stabilityStrength", 0.0).forGetter(ObserverModelData::stabilityStrength),
            Codec.STRING.optionalFieldOf("concept", "").forGetter(ObserverModelData::concept),
            Codec.INT.optionalFieldOf("progress", 0).forGetter(ObserverModelData::progress),
            Codec.INT.optionalFieldOf("bioEnergy", 0).forGetter(ObserverModelData::bioEnergy),
            Codec.BOOL.optionalFieldOf("totalStability", false).forGetter(ObserverModelData::totalStability),
            Codec.INT.optionalFieldOf("copies", 0).forGetter(ObserverModelData::copies)
    ).apply(inst, ObserverModelData::new));

    // 分量超过 composite 上限（6），手写编码
    public static final StreamCodec<ByteBuf, ObserverModelData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, data.type());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, data.trainedTargets());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, data.trainedEntities());
                buf.writeDouble(data.stabilityStrength());
                ByteBufCodecs.STRING_UTF8.encode(buf, data.concept());
                buf.writeInt(data.progress());
                buf.writeInt(data.bioEnergy());
                buf.writeBoolean(data.totalStability());
                buf.writeInt(data.copies());
            },
            buf -> new ObserverModelData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                    buf.readDouble(),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readInt()));

    /** 复制一份，代数 +1（用于复制配方与升级流程）。 */
    public ObserverModelData nextCopy() {
        return new ObserverModelData(type, trainedTargets, trainedEntities, stabilityStrength,
                concept, progress, bioEnergy, totalStability, copies + 1);
    }

    /**
     * 候选观测者是否已练满（= 兼具完全稳定效果）。
     * <p>
     * 判定放在数据 record 上，<b>服务端与客户端共用同一公式</b>：客户端只有同步过来的
     * {@code PrototypeData}，无法调用 {@code ObserverModelItem}（那需要真实的 ItemStack）。
     * 两边各写一份迟早会漂移，所以统一在这里。
     */
    public boolean candidateComplete() {
        return TYPE_CANDIDATE.equals(type) && progress >= requiredCandidatePoints(copies);
    }

    /**
     * 完成候选体所需的训练点数：基础值 + 复制代数带来的递增代价。
     * <p>
     * 客户端预览不能用这个重载（它读本端配置），要用
     * {@link com.zhizhiwang.focal_decay.mutation.MutationSettings#requiredCandidatePoints(int)} ——
     * 训练点数是"练满 = 硬保护"的判据，
     * 两端不一致就会出现"客户端以为有保护、服务端照样转换"。公式仍然只有下面一份。
     */
    public static int requiredCandidatePoints(int copies) {
        return requiredCandidatePoints(copies,
                com.zhizhiwang.focal_decay.config.FocalDecayConfig.CANDIDATE_REQUIRED_POINTS.get(),
                com.zhizhiwang.focal_decay.config.FocalDecayConfig.TOTAL_STABILITY_COPY_TRAIN_PENALTY.get());
    }

    /** 训练点数公式（纯函数：参数显式给出，供服务端配置与客户端同步快照共用）。 */
    public static int requiredCandidatePoints(int copies, int basePoints, int penaltyPerGeneration) {
        int base = Math.max(1, basePoints);
        int generation = Math.max(0, copies);
        if (generation == 0) {
            return base;
        }
        int penalty = Math.max(0, penaltyPerGeneration);
        // 代价随代数递增：1 代 ×1、2 代 ×3（三角数），强化"越失真越难校准"
        int escalating = generation * (generation + 1) / 2;
        return base + escalating * penalty;
    }

    public static ObserverModelData blank() {
        return new ObserverModelData(TYPE_BLANK, List.of(), List.of(), 0.0, "", 0, 0, false, 0);
    }

    /** 生物稳定模型：无需训练，初始能量为 0，靠范围内生物生命值补充。 */
    public static ObserverModelData bio() {
        return new ObserverModelData(TYPE_BIO, List.of(), List.of(), 1.0, "", 0, 0, false, 0);
    }

    /** 候选观测者模型：无数量上限的"空白模型"，进度从 0 开始。 */
    public static ObserverModelData candidate() {
        return new ObserverModelData(TYPE_CANDIDATE, List.of(), List.of(), 0.0, "", 0, 0, false, 0);
    }

    /**
     * 已激活的完全稳定模型（原件，copies = 0）。
     * 作为该物品注册时的默认组件——创造栏/JEI/`/give` 拿到的物品必须自带数据，
     * 否则放进基座会在 {@code radiusFor} 里 NPE。
     * <p>
     * 注意命名：不能叫 {@code totalStability()}，那会与 record 的布尔分量访问器同名并覆盖它。
     */
    public static ObserverModelData activatedTotalStability() {
        return new ObserverModelData(TYPE_TOTAL, List.of(), List.of(), 1.0, "", 0, 0, true, 0);
    }
}
