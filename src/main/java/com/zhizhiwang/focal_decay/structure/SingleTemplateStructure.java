package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

import java.util.List;
import java.util.Optional;

/**
 * "单模板"结构类型（{@code focal_decay:single_template}）：一个结构 JSON = 一个 NBT 模板 + 一排处理器。
 * <p>
 * <b>为什么需要它</b>：原版要放一个自己搭的 NBT 结构，得凑齐
 * {@code structure} + {@code template_pool} + {@code processor_list}（+ 自然生成还要 {@code structure_set} 与生物群系标签），
 * 四五份 JSON 互相引用，改一个字段要翻三个文件。这里把"一个模板"直接做成结构类型，
 * 每个结构只剩一份 JSON，处理器内联写在里面。
 * <p>
 * <b>默认自带 {@link AnchorModelProcessor}</b>：模板里的观测者基座放下去就会自带一个随机稳定模型
 * （三档战利品表，见 {@code data/focal_decay/loot_table/structure/anchor_model.json}）。
 * 不想要就显式写 {@code "processors": []}。
 * <p>
 * <b>最小用法</b>（文件放 {@code data/<命名空间>/worldgen/structure/<名字>.json}）：
 * <pre>{@code
 * {
 *   "type": "focal_decay:single_template",
 *   "template": "focal_decay:my_ruin",
 *   "biomes": "#minecraft:is_overworld",
 *   "step": "surface_structures",
 *   "spawn_overrides": {}
 * }
 * }</pre>
 * {@code spawn_overrides} 是<b>原版</b>要求必填的（{@code StructureSettings.CODEC} 里是
 * {@code fieldOf} 而不是 {@code optionalFieldOf}），漏了会直接报
 * {@code No key spawn_overrides in MapLike} 并且整个存档拒绝加载 —— 空对象 {@code {}} 就行。
 * <p>
 * NBT 本体放 {@code data/focal_decay/structure/my_ruin.nbt}（结构方块保存出来的就是它）。
 * 之后 {@code /place structure focal_decay:<名字>} 就能原地摆出来验证；要自然生成再加
 * {@code structure_set} 与生物群系标签即可，本文件不用动。
 */
public class SingleTemplateStructure extends Structure {

    /**
     * 默认处理器：给基座塞随机模型。
     * <p>
     * 这里直接放一个新实例当默认值：{@code optionalFieldOf} 的默认值只在字段缺失时使用，
     * 编解码是对称的，实例本身无状态。
     */
    public static final List<StructureProcessor> DEFAULT_PROCESSORS = List.of(new AnchorModelProcessor());

    public static final MapCodec<SingleTemplateStructure> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            settingsCodec(inst),
            ResourceLocation.CODEC
                    .fieldOf("template")
                    .forGetter(s -> s.template),
            StructureProcessorType.SINGLE_CODEC.listOf()
                    .optionalFieldOf("processors", DEFAULT_PROCESSORS)
                    .forGetter(s -> s.processors),
            Codec.INT
                    .optionalFieldOf("y_offset", 0)
                    .forGetter(s -> s.yOffset),
            Codec.INT
                    .optionalFieldOf("y")
                    .forGetter(s -> s.absoluteY),
            Rotation.CODEC
                    .optionalFieldOf("rotation", Rotation.NONE)
                    .forGetter(s -> s.rotation)
    ).apply(inst, SingleTemplateStructure::new));

    private final ResourceLocation template;
    private final List<StructureProcessor> processors;
    /** 相对地表高度的偏移（y 缺省时生效）。 */
    private final int yOffset;
    /** 写死的高度；给了就直接用，忽略 y_offset 与地形。 */
    private final Optional<Integer> absoluteY;
    private final Rotation rotation;

    public SingleTemplateStructure(StructureSettings settings, ResourceLocation template,
                                   List<StructureProcessor> processors, int yOffset,
                                   Optional<Integer> absoluteY, Rotation rotation) {
        super(settings);
        this.template = template;
        this.processors = processors;
        this.yOffset = yOffset;
        this.absoluteY = absoluteY;
        this.rotation = rotation;
    }

    /**
     * 生成点 = 区块的最小 XZ 角。
     * <p>
     * 用区块角而不是区块中心：模板的 NBT 坐标原点就是结构方块的位置，
     * 让它对齐区块角，玩家在结构方块里看到的相对坐标就能直接当"结构内偏移"用。
     */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        int x = ctx.chunkPos().getMinBlockX();
        int z = ctx.chunkPos().getMinBlockZ();
        int y = this.absoluteY.orElseGet(() -> ctx.chunkGenerator()
                .getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState())
                + this.yOffset);
        BlockPos pos = new BlockPos(x, y, z);
        return Optional.of(new GenerationStub(pos, pieces -> pieces.addPiece(
                new SingleTemplatePiece(ctx.structureTemplateManager(), this.template,
                        this.rotation, this.processors, pos))));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.SINGLE_TEMPLATE.get();
    }
}
