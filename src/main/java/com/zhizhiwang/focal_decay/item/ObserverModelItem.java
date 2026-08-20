package com.zhizhiwang.focal_decay.item;

import com.zhizhiwang.focal_decay.data.ModDataComponents;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 观测模型物品基类（设计大纲 §3.3）。
 * 训练数据/属性将在里程碑 2 通过 ObserverModelData DataComponent 存储；
 * 当前仅作为"可插入原型机插槽"的标识。
 */
public class ObserverModelItem extends Item {
    public ObserverModelItem(Properties properties) {
        super(properties);
    }

    public static ObserverModelData getData(ItemStack stack) {
        return stack.get(ModDataComponents.OBSERVER_MODEL_DATA.get());
    }

    public static ItemStack setData(ItemStack stack, ObserverModelData data) {
        stack.set(ModDataComponents.OBSERVER_MODEL_DATA.get(), data);
        return stack;
    }

    /** 是否为"训练中"模型（开始训练后、完成前的中间态）。 */
    public static boolean isTraining(ItemStack stack) {
        ObserverModelData data = getData(stack);
        return data != null && ObserverModelData.TYPE_TRAINING.equals(data.type());
    }

    /** 收纳袋风格提示：默认折叠显示数量，按住 Shift 展开训练目标列表。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        ObserverModelData data = getData(stack);
        if (data == null) {
            return;
        }
        if (ObserverModelData.TYPE_BIO.equals(data.type())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.bio_stabilizer")
                    .withStyle(ChatFormatting.DARK_GREEN));
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.bio_energy",
                    Math.max(0, data.bioEnergy())));
            return; // 生物稳定无需训练，不显示目标列表
        }
        if (ObserverModelData.TYPE_TOTAL.equals(data.type())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.total_stabilizer")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            return; // 完全稳定无需训练，不显示目标列表/Shift 提示
        }
        if (Screen.hasShiftDown()) {
            for (String target : data.trainedTargets()) {
                tooltipComponents.add(Component.literal("  ").append(blockName(target)));
            }
            for (String entity : data.trainedEntities()) {
                tooltipComponents.add(Component.literal("  ").append(entityName(entity)));
            }
        } else {
            int count = data.trainedTargets().size() + data.trainedEntities().size();
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.model_targets", count)
                    .withStyle(ChatFormatting.GRAY));
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.model_shift_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static Component blockName(String id) {
        try {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            return block != Blocks.AIR ? Component.translatable(block.getDescriptionId()) : Component.literal(id);
        } catch (Exception ignored) {
            return Component.literal(id);
        }
    }

    private static Component entityName(String id) {
        try {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
            return type.getDescription();
        } catch (Exception ignored) {
            return Component.literal(id);
        }
    }
}
