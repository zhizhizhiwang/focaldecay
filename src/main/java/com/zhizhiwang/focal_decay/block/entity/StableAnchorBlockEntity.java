package com.zhizhiwang.focal_decay.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 稳定锚的方块实体。
 * 保留它以优化遍历并防止其被纳入转换池（带方块实体的方块不参与突变）。
 */
public class StableAnchorBlockEntity extends BlockEntity {

    public StableAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STABLE_ANCHOR.get(), pos, state);
    }
}
