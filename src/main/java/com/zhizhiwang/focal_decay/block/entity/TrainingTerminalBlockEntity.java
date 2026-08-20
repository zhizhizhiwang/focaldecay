package com.zhizhiwang.focal_decay.block.entity;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.menu.TrainingTerminalMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 训练终端方块实体（设计大纲 §8.1）：
 * 槽 0 存放被训练的模型；"开始训练"后模型进入 training 中间态，
 * 玩家手持模型右键世界方块/生物收集目标，回终端"完成训练"生成成品。
 * 能量：FE 默认消耗 0（单模组不启用），仅配置开启且有能量时才消耗。
 */
public class TrainingTerminalBlockEntity extends BlockEntity implements MenuProvider, Container {
    private static final String TAG_MODEL = "Model";
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_FINAL_TYPE = "FinalType";

    public static final int TYPE_SEMANTIC_LOCK = 0;
    public static final int TYPE_GUIDED = 1;

    private ItemStack modelStack = ItemStack.EMPTY;
    private int energy;
    private int finalType = TYPE_SEMANTIC_LOCK;

    public TrainingTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TRAINING_TERMINAL.get(), pos, state);
    }

    // ---- 训练流程：开始训练 → 模型可随时取出收集目标/放回继续 → 完成训练 ----
    public boolean startTraining(Player player, int type) {
        ItemStack model = modelStack;
        if (!model.is(ModItems.OBSERVER_MODEL_BLANK.get())) {
            player.displayClientMessage(Component.translatable("message.focal_decay.training_need_blank"), true);
            return false;
        }
        int cost = FocalDecayConfig.TRAINING_ENERGY_COST.get();
        if (cost > 0 && energy < cost) {
            player.displayClientMessage(Component.translatable("message.focal_decay.training_no_energy"), true);
            return false;
        }
        if (cost > 0) {
            energy -= cost;
            setChanged();
        }
        this.finalType = type;
        ItemStack training = model.copy();
        training.setCount(1);
        ObserverModelItem.setData(training, new ObserverModelData(
                ObserverModelData.TYPE_TRAINING, List.of(), List.of(), 0.0, 0, false));
        setItem(0, training);
        return true;
    }

    public boolean finishTraining(Player player) {
        ItemStack model = modelStack;
        ObserverModelData data = model.getItem() instanceof ObserverModelItem ? ObserverModelItem.getData(model) : null;
        if (data == null || !ObserverModelData.TYPE_TRAINING.equals(data.type())) {
            player.displayClientMessage(Component.translatable("message.focal_decay.training_not_in_progress"), true);
            return false;
        }
        if (data.trainedTargets().isEmpty() && data.trainedEntities().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.focal_decay.training_no_targets"), true);
            return false;
        }
        String finalTypeName = finalType == TYPE_GUIDED
                ? ObserverModelData.TYPE_GUIDED
                : ObserverModelData.TYPE_SEMANTIC_LOCK;
        ItemStack finished = new ItemStack(
                finalType == TYPE_GUIDED ? ModItems.GUIDED_MUTATION_MODEL.get() : ModItems.SEMANTIC_LOCK_MODEL.get());
        finished.setCount(1);
        ObserverModelItem.setData(finished, new ObserverModelData(
                finalTypeName, data.trainedTargets(), data.trainedEntities(), 1.0, 0, false));
        setItem(0, finished);
        this.finalType = TYPE_SEMANTIC_LOCK;
        return true;
    }

    // ---- 能量 ----
    public int getEnergy() {
        return energy;
    }

    public void receiveEnergy(int amount) {
        if (amount <= 0) {
            return;
        }
        energy = Math.min(energy + amount, Math.max(0, FocalDecayConfig.TRAINING_ENERGY_CAPACITY.get()));
        setChanged();
    }

    // ---- MenuProvider ----
    @Override
    public Component getDisplayName() {
        return Component.translatable("container.focal_decay.training_terminal");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new TrainingTerminalMenu(containerId, playerInventory, this);
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
            modelStack = stack;
            setChanged();
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
        tag.putInt(TAG_ENERGY, energy);
        tag.putInt(TAG_FINAL_TYPE, finalType);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        modelStack = tag.contains(TAG_MODEL)
                ? ItemStack.parseOptional(registries, tag.getCompound(TAG_MODEL))
                : ItemStack.EMPTY;
        energy = tag.getInt(TAG_ENERGY);
        finalType = tag.getInt(TAG_FINAL_TYPE);
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
