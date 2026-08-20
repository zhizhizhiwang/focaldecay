package com.zhizhiwang.focal_decay.structure;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 世界生成注册（设计大纲 §2.1）：
 *  - 结构类型（STRUCTURE_TYPE）：决定结构 JSON 的 "type"；
 *  - 结构部件类型（STRUCTURE_PIECE）：决定存档里部件如何反序列化。
 */
public final class ModStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, FocalDecay.MODID);

    public static final DeferredRegister<StructurePieceType> PIECE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, FocalDecay.MODID);

    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENT_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, FocalDecay.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<?>> END_THRONE =
            STRUCTURE_TYPES.register("end_throne",
                    () -> (StructureType<ThroneStructure>) () -> ThroneStructure.CODEC);

    public static final DeferredHolder<StructurePlacementType<?>, StructurePlacementType<?>> THRONE_PLACEMENT =
            PLACEMENT_TYPES.register("end_throne_spread",
                    () -> (StructurePlacementType<ThroneStructurePlacement>) () -> ThroneStructurePlacement.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> END_THRONE_PIECE =
            PIECE_TYPES.register("end_throne_piece", () -> (StructurePieceType.ContextlessType) tag -> new ThronePiece(tag));

    private ModStructures() {
    }
}
