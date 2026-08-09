package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.block.entity.MutationControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public class MutationControllerBlock extends Block implements EntityBlock {

    public MutationControllerBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.0f)
                .sound(SoundType.METAL));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MutationControllerBlockEntity(pos, state);
    }
}
