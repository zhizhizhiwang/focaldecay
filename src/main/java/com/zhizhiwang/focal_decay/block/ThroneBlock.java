package com.zhizhiwang.focal_decay.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/**
 * 末地王座方块：构成王座结构的不可破坏方块（硬度等同基岩）。
 * 王座区域经转换黑名单注册，任何阶段都不会失焦。
 */
public class ThroneBlock extends Block {
    public ThroneBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(-1.0F, Float.MAX_VALUE)
                .noLootTable()
                .sound(SoundType.METAL)
                .pushReaction(PushReaction.BLOCK));
    }
}
