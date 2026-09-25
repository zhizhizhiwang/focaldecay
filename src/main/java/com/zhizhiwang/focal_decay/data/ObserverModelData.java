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

    /**
     * {@code copies} 的硬上限（2026-09-25，BACKLOG `P1-6`）。
     * <p>
     * 这个字段可以从 NBT / 组件 / 命令直接读入，而<b>没有任何钳制</b>；而它进入
     * {@code MutationPoolManager#totalStabilityRadius} 的三角数计算
     * （{@code generation * (generation + 1) / 2 * penalty}）。正常玩法下代数只有 0/1/2，
     * 越界值只可能来自手改存档或病态数据包——但那种输入不该让半径计算落到 int 溢出上。
     * <p>
     * 取 1024 而不是"配置里的上限"：配置（{@code total_stability_max_copies}）是<b>玩法约束</b>，
     * 而这里是<b>数据合法性约束</b>，两者不该耦合（整合包把玩法上限调到 16 时，
     * 这里也不该悄悄改写它读到的值）。
     */
    public static final int MAX_COPIES = 1024;

    /**
     * 规范化入参不变式（2026-09-25，BACKLOG `P1-6`）。写在这里而不是每个构造点各写一遍：
     * 这些字段都来自外部（NBT / 网络 / 合成），而它们各自有明确的合法域。
     * <ul>
     *   <li>{@code copies} —— 见 {@link #MAX_COPIES}；负数会让"第几代副本"这种显示与三角数计算同时失去意义。</li>
     *   <li>{@code progress} —— 候选体训练进度，负数会让进度条与完成判定错乱。</li>
     *   <li>{@code bioEnergy} —— 生物稳定模型的能量，负数会被 {@code > 0} 判定当成"已失效"，
     *       但同时又被 tooltip 直接读出来显示成负数。</li>
     *   <li>{@code stabilityStrength} —— 语义锁定的保护强度 / 引导模型的完备度 q，语义上是概率，钳到 [0,1]。</li>
     * </ul>
     * 列表字段（trainedTargets / trainedEntities）也一并做成不可变副本：它们是 item component 的一部分，
     * 在 {@code ClientPrototype} 等地方会被跨线程读到，能改的列表迟早会被谁改一下。
     */
    public ObserverModelData {
        copies = Math.max(0, Math.min(MAX_COPIES, copies));
        progress = Math.max(0, progress);
        bioEnergy = Math.max(0, bioEnergy);
        stabilityStrength = Math.max(0.0, Math.min(1.0, stabilityStrength));
        trainedTargets = List.copyOf(trainedTargets);
        trainedEntities = List.copyOf(trainedEntities);
        concept = concept == null ? "" : concept;
    }

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
     * 候选体训练进度的每点增益：下标 = 复制代数，超出表长取最后一个（2026-09-17）。
     * <p>
     * <b>为什么用增益而不是"需求点数"</b>：需求的顶点是 {@code candidate_required_points}（100%）不变，
     * 所以"副本更难练"只能体现在"每点涨得慢"上——这样进度条永远 0~100%，
     * 逐次训练的体感也正好是"训练一个方块 +1% / +0.7% / +0.5%"。
     * 副本的代价因此是<b>更长的训练清单</b>（143 / 200 个目标），不是一个更大的数字上限。
     */
    public static double candidateGain(int copies, List<? extends Number> gains) {
        if (gains == null || gains.isEmpty()) {
            return 1.0;
        }
        int index = Math.max(0, Math.min(copies, gains.size() - 1));
        Number entry = gains.get(index);
        double gain = entry == null ? 1.0 : entry.doubleValue();
        // 防御：<=0 会让需求变成无穷大（练不满），>1 会让副本比原件还快
        return gain <= 0.0 ? 1.0 : Math.min(1.0, gain);
    }

    /**
     * 练满所需的训练点数 = 顶点 / 增益（向上取整）：{@code 1.0/0.7/0.5 → 100/143/200}。
     * <p>
     * 客户端预览不能用这个重载（它读本端配置），要用服务端同步过来的"是否练满"判定
     * （{@code SyncRegionDataPacket.PrototypeData#candidateComplete}）——训练量是
     * "练满 = 硬保护"的判据，两端不一致就会出现"客户端以为有保护、服务端照样转换"。
     * 公式仍然只有下面一份。
     */
    public static int requiredCandidatePoints(int copies) {
        return requiredCandidatePoints(copies,
                com.zhizhiwang.focal_decay.config.FocalDecayConfig.CANDIDATE_REQUIRED_POINTS.get(),
                com.zhizhiwang.focal_decay.config.FocalDecayConfig.CANDIDATE_COPY_GAIN.get());
    }

    /** 训练点数公式（纯函数：参数显式给出，供服务端配置与自测共用）。 */
    public static int requiredCandidatePoints(int copies, int basePoints, List<? extends Number> gains) {
        int base = Math.max(1, basePoints);
        return (int) Math.ceil(base / candidateGain(copies, gains));
    }

    /** 显示用进度百分比（0~100）：<b>顶点恒为 100%</b>，副本只是涨得慢。 */
    public static int candidatePercent(int progress, int required) {
        if (required <= 0) {
            return 100;
        }
        return (int) Math.max(0, Math.min(100, Math.round(progress * 100.0 / required)));
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
        return candidate(0);
    }

    /**
     * 候选观测者模型（带复制代数）。
     * <p>
     * 用副本 OBSR-EX 合成出来的 OBSR-3 要<b>记住那份 EX 是第几代</b>——训练增益按它递减
     * （见 {@link #candidateGain}）。派生配方曾经是普通 shaped 配方，只能产出注册时的默认物品，
     * 代数永远丢掉，于是"副本合成的 OBSR-3 更难练"这条设计从来没有生效过。
     */
    public static ObserverModelData candidate(int copies) {
        return new ObserverModelData(TYPE_CANDIDATE, List.of(), List.of(), 0.0, "", 0, 0, false,
                Math.max(0, copies));
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
