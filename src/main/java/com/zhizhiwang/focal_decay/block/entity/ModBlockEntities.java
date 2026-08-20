package com.zhizhiwang.focal_decay.block.entity;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FocalDecay.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AnchorPrototypeBlockEntity>> ANCHOR_PROTOTYPE =
            BLOCK_ENTITY_TYPES.register("anchor_prototype", () ->
                    BlockEntityType.Builder.of(AnchorPrototypeBlockEntity::new, ModBlocks.ANCHOR_PROTOTYPE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrainingTerminalBlockEntity>> TRAINING_TERMINAL =
            BLOCK_ENTITY_TYPES.register("training_terminal", () ->
                    BlockEntityType.Builder.of(TrainingTerminalBlockEntity::new, ModBlocks.TRAINING_TERMINAL.get()).build(null));

    private ModBlockEntities() {
    }
}
