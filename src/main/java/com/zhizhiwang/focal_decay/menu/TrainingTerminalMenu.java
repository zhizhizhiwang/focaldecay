package com.zhizhiwang.focal_decay.menu;

import com.zhizhiwang.focal_decay.block.entity.TrainingTerminalBlockEntity;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
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
 * 训练终端 GUI（设计大纲 §3.2）：1 个模型槽 + 能量显示 + 训练按钮。
 * 按钮：0 = 开始训练(语义锁定)，1 = 开始训练(引导)，2 = 完成训练。
 */
public class TrainingTerminalMenu extends AbstractContainerMenu {
    private final Container modelContainer;
    private final ContainerData energyData;

    /** 客户端侧占位构造。 */
    public TrainingTerminalMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(1), new SimpleContainerData(1));
    }

    /** 服务端构造。 */
    public TrainingTerminalMenu(int containerId, Inventory playerInventory, TrainingTerminalBlockEntity be) {
        this(containerId, playerInventory, be, new ContainerData() {
            @Override
            public int get(int index) {
                return index == 0 ? be.getEnergy() : 0;
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 1;
            }
        });
    }

    private TrainingTerminalMenu(int containerId, Inventory playerInventory, Container modelContainer, ContainerData energyData) {
        super(ModMenus.TRAINING_TERMINAL.get(), containerId);
        this.modelContainer = modelContainer;
        this.energyData = energyData;
        checkContainerSize(modelContainer, 1);
        modelContainer.startOpen(playerInventory.player);

        // 模型槽
        this.addSlot(new Slot(modelContainer, 0, 80, 70) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof ObserverModelItem;
            }
        });

        // 玩家主背包 27
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 96 + row * 18));
            }
        }
        // 快捷栏 9
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 154));
        }
        this.addDataSlots(energyData);
    }

    public int getEnergy() {
        return energyData.get(0);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (modelContainer instanceof TrainingTerminalBlockEntity be) {
            return switch (id) {
                case 0 -> be.startTraining(player, TrainingTerminalBlockEntity.TYPE_SEMANTIC_LOCK);
                case 1 -> be.startTraining(player, TrainingTerminalBlockEntity.TYPE_GUIDED);
                case 2 -> be.finishTraining(player);
                default -> false;
            };
        }
        return false;
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
