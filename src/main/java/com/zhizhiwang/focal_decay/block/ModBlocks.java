package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FocalDecay.MODID);

    public static final DeferredBlock<AnchorPrototypeBlock> ANCHOR_PROTOTYPE = BLOCKS.register("anchor_prototype", AnchorPrototypeBlock::new);
    public static final DeferredBlock<TrainingTerminalBlock> TRAINING_TERMINAL = BLOCKS.register("training_terminal", TrainingTerminalBlock::new);
    public static final DeferredBlock<ObserverCoreBlock> OBSERVER_CORE = BLOCKS.register("observer_core", ObserverCoreBlock::new);
    public static final DeferredBlock<ThroneBlock> THRONE_BLOCK = BLOCKS.register("throne_block", ThroneBlock::new);

    private ModBlocks() {
    }
}
