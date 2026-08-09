package com.zhizhiwang.focal_decay.block;

import java.util.List;
import com.zhizhiwang.focal_decay.block.entity.StableAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public class StableAnchorBlock extends Block implements EntityBlock {
    /** 切比雪夫距离保护半径，可被配置覆盖。 */
    public static final int PROTECTION_RADIUS = 8;

    public StableAnchorBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.0f)
                .sound(SoundType.METAL)
                .lightLevel(state -> 7));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StableAnchorBlockEntity(pos, state);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("tooltip.focal_decay.stable_anchor"));
    }
}
