package com.zhizhiwang.focal_decay.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 突变控制器方块实体。
 * 存储标签表达式与作用半径，覆盖逻辑由服务端 MutationPoolManager 消费。
 * 后续将实现 MenuProvider 以提供 GUI。
 */
public class MutationControllerBlockEntity extends BlockEntity {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 32;
    public static final int DEFAULT_RADIUS = 16;

    private static final String TAG_EXPRESSION = "TagExpression";
    private static final String TAG_RADIUS = "Radius";

    private String tagExpression = "";
    private int radius = DEFAULT_RADIUS;

    public MutationControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MUTATION_CONTROLLER.get(), pos, state);
    }

    public String getTagExpression() {
        return tagExpression;
    }

    public void setTagExpression(String tagExpression) {
        this.tagExpression = tagExpression == null ? "" : tagExpression;
        setChanged();
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_EXPRESSION, tagExpression);
        tag.putInt(TAG_RADIUS, radius);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tagExpression = tag.getString(TAG_EXPRESSION);
        radius = tag.getInt(TAG_RADIUS);
    }
}
