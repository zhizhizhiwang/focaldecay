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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StableAnchorBlockEntity>> STABLE_ANCHOR =
            BLOCK_ENTITY_TYPES.register("stable_anchor", () ->
                    BlockEntityType.Builder.of(StableAnchorBlockEntity::new, ModBlocks.STABLE_ANCHOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MutationControllerBlockEntity>> MUTATION_CONTROLLER =
            BLOCK_ENTITY_TYPES.register("mutation_controller", () ->
                    BlockEntityType.Builder.of(MutationControllerBlockEntity::new, ModBlocks.MUTATION_CONTROLLER.get()).build(null));

    private ModBlockEntities() {
    }
}
