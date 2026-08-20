package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.block.entity.TrainingTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 训练终端（设计大纲 §3.2）：放入空白模型，训练语义锁定/引导模型。
 */
public class TrainingTerminalBlock extends Block implements EntityBlock {

    public TrainingTerminalBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.0f)
                .sound(SoundType.METAL));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrainingTerminalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof TrainingTerminalBlockEntity be) {
                player.openMenu(be);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof TrainingTerminalBlockEntity be) {
                Containers.dropContents(level, pos, be);
            }
            super.onRemove(state, level, pos, newState, moved);
        }
    }
}
