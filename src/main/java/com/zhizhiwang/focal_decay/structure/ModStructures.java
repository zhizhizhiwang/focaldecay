package com.zhizhiwang.focal_decay.structure;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
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

    /**
     * "单模板"结构类型：一份 JSON = 一个 NBT 模板（+ 内联处理器），
     * 不需要 template_pool / processor_list。见 {@link SingleTemplateStructure}。
     */
    public static final DeferredHolder<StructureType<?>, StructureType<?>> SINGLE_TEMPLATE =
            STRUCTURE_TYPES.register("single_template",
                    () -> (StructureType<SingleTemplateStructure>) () -> SingleTemplateStructure.CODEC);

    public static final DeferredHolder<StructurePlacementType<?>, StructurePlacementType<?>> THRONE_PLACEMENT =
            PLACEMENT_TYPES.register("end_throne_spread",
                    () -> (StructurePlacementType<ThroneStructurePlacement>) () -> ThroneStructurePlacement.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> END_THRONE_PIECE =
            PIECE_TYPES.register("end_throne_piece", () -> (StructurePieceType.ContextlessType) tag -> new ThronePiece(tag));

    /** {@link SingleTemplatePiece} 的部件类型（需要模板管理器，故用 StructureTemplateType）。 */
    public static final DeferredHolder<StructurePieceType, StructurePieceType> SINGLE_TEMPLATE_PIECE =
            PIECE_TYPES.register("single_template_piece",
                    () -> (StructurePieceType.StructureTemplateType) SingleTemplatePiece::new);

    /**
     * 结构处理器类型：给结构里的观测者基座塞入随机稳定模型。
     * <p>
     * 注册名 {@code focal_decay:anchor_model}，在结构 JSON 的 {@code processors} 里引用即可。
     */
    public static final DeferredRegister<StructureProcessorType<?>> PROCESSOR_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, FocalDecay.MODID);

    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<?>> ANCHOR_MODEL_PROCESSOR =
            PROCESSOR_TYPES.register("anchor_model",
                    () -> (StructureProcessorType<AnchorModelProcessor>) () -> AnchorModelProcessor.CODEC);

    private ModStructures() {
    }
}
