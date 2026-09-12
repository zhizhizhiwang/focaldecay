package com.zhizhiwang.focal_decay.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * <b>旧版王座的部件类型，现在只用于让旧存档还能反序列化。</b>
 * <p>
 * 2026-09-12 起王座本体改成数据驱动的结构模板（{@code end_throne.nbt}），
 * 由 {@link SingleTemplatePiece} 摆放，本类不再参与生成。
 * <p>
 * 那为什么不直接删掉？因为已经生成过王座的存档，区块 NBT 里存着
 * {@code focal_decay:end_throne_piece} 这个 id；把它从注册表里摘掉的话，
 * 载入那些区块时 {@code StructureStart.loadStaticStart} 会抛异常并刷
 * "Failed Start with id focal_decay:end_throne" 的错误日志。
 * 留一个空壳（放着不管就行，反正旧结构不会再被重新生成）代价最低。
 */
public class ThronePiece extends StructurePiece {

    public ThronePiece(BlockPos origin) {
        super(ModStructures.END_THRONE_PIECE.get(), 0, new BoundingBox(
                origin.getX() - ThroneStructure.HALF_X,
                origin.getY() - ThroneStructure.BELOW,
                origin.getZ() - ThroneStructure.HALF_Z,
                origin.getX() + ThroneStructure.HALF_X,
                origin.getY() + ThroneStructure.ABOVE,
                origin.getZ() + ThroneStructure.HALF_Z));
    }

    public ThronePiece(CompoundTag tag) {
        super(ModStructures.END_THRONE_PIECE.get(), tag);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        // 无额外数据：位置/包围盒由父类持久化
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos anchor) {
        // 旧版在这里逐块摆放王座；现在什么也不做（新结构走 SingleTemplatePiece）。
    }
}
