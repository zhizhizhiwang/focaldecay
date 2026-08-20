package com.zhizhiwang.focal_decay.block.entity;

import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.menu.AnchorPrototypeMenu;
import com.zhizhiwang.focal_decay.mutation.MutationEventHandler;
import com.zhizhiwang.focal_decay.mutation.MutationPoolManager;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 稳定锚原型机的方块实体（设计大纲 §8.1）：
 * 存储插入的观测模型；模型效果在里程碑 3 接入。
 */
public class AnchorPrototypeBlockEntity extends BlockEntity implements MenuProvider, Container {
    private static final String TAG_MODEL = "Model";

    private ItemStack modelStack = ItemStack.EMPTY;

    public AnchorPrototypeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ANCHOR_PROTOTYPE.get(), pos, state);
    }

    public ItemStack getModelStack() {
        return modelStack;
    }

    /** 是否插入了有效模型（非空且非空白模型）。 */
    public boolean hasActiveModel() {
        ObserverModelData data = ObserverModelItem.getData(modelStack);
        return data != null && !ObserverModelData.TYPE_BLANK.equals(data.type());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            MutationPoolManager.get(serverLevel).updatePrototypeEffect(worldPosition, modelStack);
        }
    }

    // ---- MenuProvider ----
    @Override
    public Component getDisplayName() {
        return Component.translatable("container.focal_decay.anchor_prototype");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AnchorPrototypeMenu(containerId, playerInventory, this);
    }

    // ---- Container ----
    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return modelStack.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? modelStack : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot == 0 && !modelStack.isEmpty()) {
            return modelStack.split(amount);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot == 0) {
            ItemStack stack = modelStack;
            modelStack = ItemStack.EMPTY;
            setChanged();
            return stack;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) {
            boolean wasActive = hasActiveModel();
            modelStack = stack;
            setChanged();
            if (level instanceof ServerLevel serverLevel) {
                MutationPoolManager manager = MutationPoolManager.get(serverLevel);
                manager.updatePrototypeEffect(worldPosition, modelStack);
                // 首次插入有效模型：固化当前范围失焦状态并广播
                if (!wasActive && hasActiveModel()) {
                    MutationEventHandler.convertPrototypeRange(serverLevel, worldPosition, manager);
                }
                ModNetwork.sendRegionDataToDimension(serverLevel);
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this;
    }

    @Override
    public void clearContent() {
        modelStack = ItemStack.EMPTY;
        setChanged();
    }

    // ---- NBT / 同步 ----
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!modelStack.isEmpty()) {
            tag.put(TAG_MODEL, modelStack.saveOptional(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        modelStack = tag.contains(TAG_MODEL)
                ? ItemStack.parseOptional(registries, tag.getCompound(TAG_MODEL))
                : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (!modelStack.isEmpty()) {
            tag.put(TAG_MODEL, modelStack.saveOptional(registries));
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        modelStack = tag.contains(TAG_MODEL)
                ? ItemStack.parseOptional(registries, tag.getCompound(TAG_MODEL))
                : ItemStack.EMPTY;
    }
}
