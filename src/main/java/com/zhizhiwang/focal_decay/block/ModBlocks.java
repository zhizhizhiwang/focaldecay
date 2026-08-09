package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FocalDecay.MODID);

    public static final DeferredBlock<StableAnchorBlock> STABLE_ANCHOR = BLOCKS.register("stable_anchor", StableAnchorBlock::new);
    public static final DeferredBlock<MutationControllerBlock> MUTATION_CONTROLLER = BLOCKS.register("mutation_controller", MutationControllerBlock::new);
    public static final DeferredBlock<ObserverCoreBlock> OBSERVER_CORE = BLOCKS.register("observer_core", ObserverCoreBlock::new);

    private ModBlocks() {
    }
}
