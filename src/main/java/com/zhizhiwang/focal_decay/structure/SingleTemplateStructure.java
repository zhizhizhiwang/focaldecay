package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhizhiwang.focal_decay.FocalDecay;
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
 * <p>
 * <b>高度三选一</b>（优先级从高到低）：
 * <ol>
 *   <li>{@code "y": 70} —— 写死高度，忽略地形；</li>
 *   <li>{@code "depth_range": [1, 8]} —— <b>埋进地下</b>：让模板的<b>最顶层方块</b>落在地表最高方块
 *       以下 {@code 1~8} 格（每次生成在范围内随机取，取的是区块自己的确定性随机源，同种子可复现）；</li>
 *   <li>{@code "y_offset": -3} —— 贴着地表，地表高度 + 偏移。</li>
 * </ol>
 * {@code depth_range} 用的是模板真实高度（从 {@code StructureTemplateManager} 读），
 * 所以模板加高一层，埋深不会跟着变 —— 不需要手算偏移。
 */
public class SingleTemplateStructure extends Structure {

    /**
     * 默认处理器：给基座塞随机模型。
     * <p>
     * 这里直接放一个新实例当默认值：{@code optionalFieldOf} 的默认值只在字段缺失时使用，
     * 编解码是对称的，实例本身无状态。
     */
    public static final List<StructureProcessor> DEFAULT_PROCESSORS = List.of(new AnchorModelProcessor());

    /**
     * {@code "depth_range": [最小深度, 最大深度]}。
     * <p>
     * 两个数写反了不报错，直接取小者为最小、大者为最大（手写 JSON 时很难记住顺序）。
     * 个数不对则<b>报错</b>而不是猜：静默取一个默认深度会让"结构跑到地表上"这种问题极难定位。
     */
    private static final Codec<int[]> DEPTH_RANGE_CODEC = Codec.INT.listOf().comapFlatMap(
            list -> {
                if (list.size() != 2) {
                    return DataResult.error(() -> "depth_range 需要正好两个整数 [最小深度, 最大深度]，实际给了 "
                            + list.size() + " 个");
                }
                int a = Math.max(0, list.get(0));
                int b = Math.max(0, list.get(1));
                return DataResult.success(new int[]{Math.min(a, b), Math.max(a, b)});
            },
            range -> List.of(range[0], range[1]));

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
                    .forGetter(s -> s.rotation),
            DEPTH_RANGE_CODEC
                    .optionalFieldOf("depth_range")
                    .forGetter(s -> s.depthRange)
    ).apply(inst, SingleTemplateStructure::new));

    private final ResourceLocation template;
    private final List<StructureProcessor> processors;
    /** 相对地表高度的偏移（y 与 depth_range 都缺省时生效）。 */
    private final int yOffset;
    /** 写死的高度；给了就直接用，忽略 depth_range 与地形。 */
    private final Optional<Integer> absoluteY;
    private final Rotation rotation;
    /** 埋深范围 [最小, 最大]，含义见类注释。 */
    private final Optional<int[]> depthRange;

    public SingleTemplateStructure(StructureSettings settings, ResourceLocation template,
                                   List<StructureProcessor> processors, int yOffset,
                                   Optional<Integer> absoluteY, Rotation rotation,
                                   Optional<int[]> depthRange) {
        super(settings);
        this.template = template;
        this.processors = processors;
        this.yOffset = yOffset;
        this.absoluteY = absoluteY;
        this.rotation = rotation;
        this.depthRange = depthRange;
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
        int y = this.absoluteY.orElseGet(() -> this.depthRange
                .map(range -> buriedY(ctx, x, z, range))
                .orElseGet(() -> surfaceY(ctx, x, z) + this.yOffset));
        BlockPos pos = new BlockPos(x, y, z);
        return Optional.of(new GenerationStub(pos, pieces -> pieces.addPiece(
                new SingleTemplatePiece(ctx.structureTemplateManager(), this.template,
                        this.rotation, this.processors, pos))));
    }

    /**
     * 地表第一格空气的高度。
     * <p>
     * 用 {@code WORLD_SURFACE_WG}（只看地形噪声、不看已放置的方块）而不是 {@code WORLD_SURFACE}：
     * 结构生成发生在装饰阶段，此时目标区块往往还没放好地形，读实时高度会拿到 0。
     */
    private int surfaceY(GenerationContext ctx, int x, int z) {
        return ctx.chunkGenerator().getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                ctx.heightAccessor(), ctx.randomState());
    }

    /**
     * 按 {@code depth_range} 把结构埋到地下。
     * <p>
     * 推导：{@code surface} 是地表第一格<b>空气</b>，所以地表最高方块在 {@code surface - 1}；
     * 想让模板最顶层方块比它低 {@code depth} 格，则最顶层方块在 {@code surface - 1 - depth}；
     * 模板最顶层方块又是 {@code originY + height - 1}，两式相减得
     * {@code originY = surface - depth - height}。
     * <p>
     * 最后一个 {@code max} 是保险：深度范围配得过大时把结构按在世界最低高度上，
     * 而不是让它掉出世界（那样整个结构会静默丢失）。
     */
    private int buriedY(GenerationContext ctx, int x, int z, int[] range) {
        int surface = surfaceY(ctx, x, z);
        int span = range[1] - range[0];
        int depth = range[0] + (span > 0 ? ctx.random().nextInt(span + 1) : 0);
        int height = ctx.structureTemplateManager().get(this.template)
                .map(template -> template.getSize().getY())
                .orElse(0);
        int y = Math.max(ctx.heightAccessor().getMinBuildHeight(), surface - depth - height);
        // 埋深只有一个数字能验证"对不对"，而它取决于地形噪声、模板高度和随机深度三样东西。
        // 出过一次"结构掉到世界底部"的事故，所以这里固定留一行日志：埋深配错时一眼就能看出来。
        // 触发频率 = 每个候选区块一次（生产间距 spacing=34），可以接受。
        FocalDecay.LOGGER.info("[focal_decay] {} buried: x={} z={} surface={} depth={} templateHeight={} -> originY={}",
                this.template, x, z, surface, depth, height, y);
        return y;
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.SINGLE_TEMPLATE.get();
    }
}
