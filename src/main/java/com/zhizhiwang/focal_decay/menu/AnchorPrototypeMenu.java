package com.zhizhiwang.focal_decay.menu;

import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 原型机 GUI（设计大纲 §3.1）：1 个模型插槽 + 玩家背包。
 */
public class AnchorPrototypeMenu extends AbstractContainerMenu {
    private final Container modelContainer;
    private final ContainerData energyData;

    /** 客户端侧占位构造。 */
    public AnchorPrototypeMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(1), new SimpleContainerData(2));
    }

    /** 服务端构造：数据槽 0 = 生物能量，1 = 能量容量。 */
    public AnchorPrototypeMenu(int containerId, Inventory playerInventory, Container modelContainer) {
        this(containerId, playerInventory, modelContainer, new ContainerData() {
            @Override
            public int get(int index) {
                if (modelContainer instanceof AnchorPrototypeBlockEntity be) {
                    return index == 0 ? be.getBioEnergy() : be.getBioCapacity();
                }
                return 0;
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 2;
            }
        });
    }

    private AnchorPrototypeMenu(int containerId, Inventory playerInventory,
                                Container modelContainer, ContainerData energyData) {
        super(ModMenus.ANCHOR_PROTOTYPE.get(), containerId);
        this.modelContainer = modelContainer;
        this.energyData = energyData;
        checkContainerSize(modelContainer, 1);
        modelContainer.startOpen(playerInventory.player);

        // 模型插槽
        this.addSlot(new Slot(modelContainer, 0, 80, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof ObserverModelItem;
            }
        });

        // 玩家主背包 27
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // 快捷栏 9
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        this.addDataSlots(energyData);
    }

    public int getBioEnergy() {
        return energyData.get(0);
    }

    public int getBioCapacity() {
        return energyData.get(1);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            itemstack = stack.copy();
            if (index == 0) {
                if (!this.moveItemStackTo(stack, 1, 37, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return modelContainer.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        modelContainer.stopOpen(player);
    }
}
