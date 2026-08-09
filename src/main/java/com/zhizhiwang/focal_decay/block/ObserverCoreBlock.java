package com.zhizhiwang.focal_decay.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public class ObserverCoreBlock extends Block {
    /** powered=false 表示失效（失焦进行中），powered=true 表示已激活（失焦终止）。 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public ObserverCoreBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(-1.0f, Float.MAX_VALUE)
                .noLootTable()
                .sound(SoundType.METAL)
                .lightLevel(state -> state.getValue(POWERED) ? 15 : 0));
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }
}
