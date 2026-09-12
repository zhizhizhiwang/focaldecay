package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.MapCodec;
import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.List;
import java.util.Optional;

/**
 * 末地王座结构（设计大纲 §2.1）：
 * 每末地维度仅一个，位置由世界种子决定（方向 + 距离），生成于主岛与外岛之间的虚空环带，
 * 远离末影龙战斗半径。结构集（random_spread）会为很多候选区块调用本结构，
 * 但只有包含王座原点的区块返回生成点——天然保证"全维度唯一"。
 * <p>
 * <b>本体是数据驱动的结构模板</b>（{@code data/focal_decay/structure/end_throne.nbt}，
 * 13×18×13，结构方块直接导出），不再由代码逐块摆放 —— 改外观只需在游戏里重搭再导出，
 * 不用碰 Java。摆放位置见 {@link #TEMPLATE_OFFSET_X}。
 */
public class ThroneStructure extends Structure {
    public static final MapCodec<ThroneStructure> CODEC = simpleCodec(ThroneStructure::new);

    /** 王座盐值（"THRONE"），与方块转换的确定性随机错开。 */
    private static final long THRONE_SALT = 0x5448524F4E45L;
    /** 基座平台中心所在 Y（末地虚空漂浮）。 */
    public static final int BASE_Y = 70;

    /** 结构模板 id（对应 {@code data/focal_decay/structure/end_throne.nbt}）。 */
    public static final ResourceLocation TEMPLATE =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "end_throne");

    /**
     * 模板摆放偏移：把模板的"中心列"对齐到王座原点。
     * <p>
     * 模板是 13 宽（列 0..12），中心列是 6，所以整体左上移 6 格。
     * 这样模板里的关键方块落点与旧代码版<b>完全一致</b>：
     * <ul>
     *   <li>{@code observer_core} 模板 (6,1,6) → 原点 +(0,1,0)</li>
     *   <li>宝箱 模板 (6,1,8) → 原点 +(0,1,2)</li>
     *   <li>四角柱顶末地棒 模板 (±6,17,±6) → 原点 +(±6,17,±6)</li>
     * </ul>
     * 因此 {@code ThroneRitualHandler}（{@code corePos = throne.offset(0,1,0)}）与
     * {@code ThroneBeamRenderer}（四角 ±6、光束起点 +17）里的硬编码偏移都不用改。
     * <b>重搭模板时必须保持这些相对位置</b>，否则要同步改那两处。
     */
    private static final int TEMPLATE_OFFSET_X = -6;
    private static final int TEMPLATE_OFFSET_Z = -6;

    /** 结构包围盒（= 模板实际占据范围，以原点为中心）。与 NBT 的 size 13×18×13 对应。 */
    public static final int HALF_X = 6;
    public static final int HALF_Z = 6;
    public static final int BELOW = 0;
    public static final int ABOVE = 17;

    public ThroneStructure(StructureSettings settings) {
        super(settings);
    }

    /**
     * 由世界种子确定王座位置：方向（32 位随机） + 距离（1400~1455 块，虚空环带）。
     * 服务端仪式逻辑与结构生成共用，保证右键位置可预测。
     */
    public static BlockPos thronePos(long worldSeed) {
        long h = MutationHelper.mix64(worldSeed ^ THRONE_SALT);
        double angle = ((h >>> 32) & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        // 虚空环带：主岛 1000 格以内、远离主岛地形（650~905 格），避免落在 1024+ 的外岛边缘
        int distance = 650 + (int) ((h >>> 48) & 0xFF);
        int x = (int) Math.round(Math.cos(angle) * distance);
        int z = (int) Math.round(Math.sin(angle) * distance);
        return new BlockPos(x, BASE_Y, z);
    }

    /** 王座所在区块（结构放置与 /locate 共用）。 */
    public static ChunkPos throneChunk(long worldSeed) {
        BlockPos pos = thronePos(worldSeed);
        return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** 判断方块是否位于王座结构包围盒内（用于不可破坏保护）。 */
    public static boolean insideThrone(BlockPos pos, BlockPos throne) {
        return Math.abs(pos.getX() - throne.getX()) <= HALF_X
                && pos.getY() >= throne.getY() - BELOW && pos.getY() <= throne.getY() + ABOVE
                && Math.abs(pos.getZ() - throne.getZ()) <= HALF_Z;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        BlockPos pos = thronePos(context.seed());
        ChunkPos chunk = context.chunkPos();
        if (chunk.x != pos.getX() >> 4 || chunk.z != pos.getZ() >> 4) {
            return Optional.empty();
        }
        // 无处理器：王座里没有观测者基座，不需要塞随机模型（2026-09-12 与用户确认）
        BlockPos templatePos = pos.offset(TEMPLATE_OFFSET_X, 0, TEMPLATE_OFFSET_Z);
        FocalDecay.LOGGER.info("End Throne structure start at {} (template at {}), chunk {}, {}, seed {}",
                pos.toShortString(), templatePos.toShortString(), chunk.x, chunk.z, context.seed());
        return Optional.of(new GenerationStub(pos, builder -> builder.addPiece(
                new SingleTemplatePiece(context.structureTemplateManager(), TEMPLATE,
                        Rotation.NONE, List.of(), templatePos))));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.END_THRONE.get();
    }
}
