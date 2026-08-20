package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.MapCodec;
import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/**
 * 末地王座结构（设计大纲 §2.1）：
 * 每末地维度仅一个，位置由世界种子决定（方向 + 距离），生成于主岛与外岛之间的虚空环带，
 * 远离末影龙战斗半径。结构集（random_spread）会为很多候选区块调用本结构，
 * 但只有包含王座原点的区块返回生成点——天然保证"全维度唯一"。
 */
public class ThroneStructure extends Structure {
    public static final MapCodec<ThroneStructure> CODEC = simpleCodec(ThroneStructure::new);

    /** 王座盐值（"THRONE"），与方块转换的确定性随机错开。 */
    private static final long THRONE_SALT = 0x5448524F4E45L;
    /** 基座平台中心所在 Y（末地虚空漂浮）。 */
    public static final int BASE_Y = 70;
    /** 结构包围盒半宽（x/z）。 */
    public static final int HALF_X = 7;
    public static final int HALF_Z = 7;
    /** 结构包围盒从 BASE_Y 向下/向上的范围。 */
    public static final int BELOW = 1;
    public static final int ABOVE = 19;

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
        FocalDecay.LOGGER.info("End Throne structure start at {} (chunk {}, {}), seed {}",
                pos.toShortString(), chunk.x, chunk.z, context.seed());
        return Optional.of(new GenerationStub(pos, builder -> builder.addPiece(new ThronePiece(pos))));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.END_THRONE.get();
    }
}
