package com.zhizhiwang.focal_decay.structure;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * 末地王座结构部件：黑曜石基座 + 四角/四边"末地水晶"柱 + 中央空基座 + 北侧王座。
 * 在末地虚空带中漂浮（BASE_Y），全部绝对坐标生成，只放置在本区块包围盒内。
 */
public class ThronePiece extends StructurePiece {
    private static final BlockState THRONE_BLOCK = ModBlocks.THRONE_BLOCK.get().defaultBlockState();
    private static final BlockState END_ROD = Blocks.END_ROD.defaultBlockState();

    public ThronePiece(BlockPos origin) {
        super(ModStructures.END_THRONE_PIECE.get(), 0, makeBox(origin));
    }

    public ThronePiece(CompoundTag tag) {
        super(ModStructures.END_THRONE_PIECE.get(), tag);
    }

    private static BoundingBox makeBox(BlockPos origin) {
        return new BoundingBox(
                origin.getX() - ThroneStructure.HALF_X, origin.getY() - ThroneStructure.BELOW, origin.getZ() - ThroneStructure.HALF_Z,
                origin.getX() + ThroneStructure.HALF_X, origin.getY() + ThroneStructure.ABOVE, origin.getZ() + ThroneStructure.HALF_Z);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        // 无额外数据：位置/包围盒由父类持久化
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos anchor) {
        BlockPos origin = new BlockPos(
                this.boundingBox.minX() + ThroneStructure.HALF_X,
                this.boundingBox.minY() + ThroneStructure.BELOW,
                this.boundingBox.minZ() + ThroneStructure.HALF_Z);
        int minX = Math.max(this.boundingBox.minX(), chunkBox.minX());
        int maxX = Math.min(this.boundingBox.maxX(), chunkBox.maxX());
        int minY = Math.max(this.boundingBox.minY(), chunkBox.minY());
        int maxY = Math.min(this.boundingBox.maxY(), chunkBox.maxY());
        int minZ = Math.max(this.boundingBox.minZ(), chunkBox.minZ());
        int maxZ = Math.min(this.boundingBox.maxZ(), chunkBox.maxZ());

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState state = stateAt(x - origin.getX(), y - origin.getY(), z - origin.getZ());
                    if (state != null) {
                        level.setBlock(new BlockPos(x, y, z), state, 3);
                    }
                }
            }
        }
    }

    /** 本地坐标（dx,dy,dz，原点 = 基座中心）到方块的映射；null 表示留空。 */
    private static BlockState stateAt(int dx, int dy, int dz) {
        // 基座：y=0 整层黑曜石，y=1 环带 + 中央空基座围边
        if (dy == 0) {
            return Math.abs(dx) <= ThroneStructure.HALF_X && Math.abs(dz) <= ThroneStructure.HALF_Z ? THRONE_BLOCK : null;
        }
        if (dy == 1) {
            boolean rim = Math.max(Math.abs(dx), Math.abs(dz)) == ThroneStructure.HALF_X;
            boolean innerRing = Math.abs(dx) == 3 || Math.abs(dz) == 3;
            boolean seat = dz == -4 && Math.abs(dx) <= 1;
            boolean armrest = dz == -4 && Math.abs(dx) == 2;
            if (rim || innerRing || seat || armrest) {
                return THRONE_BLOCK;
            }
            return null;
        }
        // 王座靠背与扶手（北侧）
        if (dz == -5 && Math.abs(dx) <= 1 && dy >= 2 && dy <= 3) {
            return THRONE_BLOCK;
        }
        if (dz == -4 && Math.abs(dx) == 2 && dy == 2) {
            return THRONE_BLOCK;
        }
        if (dz == -5 && dx == 0 && dy == 4) {
            return END_ROD;
        }
        // 四角高柱（"末地水晶"）
        if (Math.abs(dx) == 6 && Math.abs(dz) == 6) {
            if (dy >= 0 && dy <= 17) {
                return THRONE_BLOCK;
            }
            if (dy == 18) {
                return END_ROD;
            }
        }
        // 四边中柱
        if ((dx == 0 && Math.abs(dz) == 6) || (Math.abs(dx) == 6 && dz == 0)) {
            if (dy >= 0 && dy <= 13) {
                return THRONE_BLOCK;
            }
            if (dy == 14) {
                return END_ROD;
            }
        }
        return null;
    }
}
