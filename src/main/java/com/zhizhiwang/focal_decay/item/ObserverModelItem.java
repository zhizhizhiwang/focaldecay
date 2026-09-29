package com.zhizhiwang.focal_decay.item;

import com.zhizhiwang.focal_decay.data.ModDataComponents;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.mutation.GuidedConcept;
import com.zhizhiwang.focal_decay.item.ModItems;
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

    /** 是否为候选观测者模型（可手持右键收集训练目标）。 */
    public static boolean isCandidate(ItemStack stack) {
        ObserverModelData data = getData(stack);
        return data != null && ObserverModelData.TYPE_CANDIDATE.equals(data.type());
    }

    /** 候选观测者是否已完成训练（进度达到要求 = 兼具完全稳定效果，可在核心安装）。 */
    public static boolean isCompletedCandidate(ItemStack stack) {
        ObserverModelData data = getData(stack);
        return data != null && ObserverModelData.TYPE_CANDIDATE.equals(data.type())
                && data.progress() >= requiredCandidatePoints(data);
    }

    /**
     * 该候选体完成所需的训练点数（委托给 {@link ObserverModelData#requiredCandidatePoints(int)}，
     * 保证服务端与客户端共用同一公式）。
     */
    public static int requiredCandidatePoints(ObserverModelData data) {
        return ObserverModelData.requiredCandidatePoints(data.copies());
    }

    /** 收纳袋风格提示：默认折叠显示数量，按住 Shift 展开训练目标列表。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        ObserverModelData data = getData(stack);

        // 复制代数：损耗必须让玩家看得见，否则"半径变小"会显得莫名其妙。
        // 候选体（OBSR-3）只写"第几代"——它是**派生自**某代 OBSR-EX，不是副本本身，
        // 训练量的差别留给手册（型号规格页），tooltip 不展开。
        if (data != null && data.copies() > 0) {
            boolean candidate = ObserverModelData.TYPE_CANDIDATE.equals(data.type());
            tooltipComponents.add(Component.translatable(candidate
                            ? "tooltip.focal_decay.candidate_generation"
                            : "tooltip.focal_decay.model_copies", data.copies())
                    .withStyle(candidate ? ChatFormatting.GOLD : ChatFormatting.RED));
        }

        // 静态 lore：按物品本身判断（未训练/无数据组件时也能显示）
        if (stack.is(ModItems.OBSERVER_MODEL_BLANK.get())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.observer_model_blank")
                    .withStyle(ChatFormatting.GRAY));
            // 下一步指引：空白模型是"不知道该怎么用"最典型的物品
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.model_next_step")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (stack.is(ModItems.SEMANTIC_LOCK_MODEL.get())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.semantic_lock_model")
                    .withStyle(ChatFormatting.AQUA));
        } else if (stack.is(ModItems.GUIDED_MUTATION_MODEL.get())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.guided_mutation_model")
                    .withStyle(ChatFormatting.AQUA));
        } else if (stack.is(ModItems.TOTAL_STABILITY_MODEL.get())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.total_stability_model")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            return;
        } else if (stack.is(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.total_stability_model_activated")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.total_stabilizer")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        } else if (stack.is(ModItems.OBSERVER_MODEL_CANDIDATE.get())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.observer_model_candidate")
                    .withStyle(ChatFormatting.AQUA));
        }

        if (data == null) {
            return; // 未训练/无数据：只有静态 lore
        }
        if (ObserverModelData.TYPE_BIO.equals(data.type())) {
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.bio_stabilizer")
                    .withStyle(ChatFormatting.DARK_GREEN));
            tooltipComponents.add(Component.translatable("tooltip.focal_decay.bio_energy",
                    Math.max(0, data.bioEnergy())));
            return; // 生物稳定无需训练，不显示目标列表
        }
        if (ObserverModelData.TYPE_CANDIDATE.equals(data.type())) {
            if (isCompletedCandidate(stack)) {
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.candidate_complete")
                        .withStyle(ChatFormatting.DARK_PURPLE));
            } else {
                // 百分比由 candidatePercent 统一算：顶点恒为 100%，副本只是涨得慢
                int percent = ObserverModelData.candidatePercent(data.progress(), requiredCandidatePoints(data));
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.candidate_progress", percent)
                        .withStyle(ChatFormatting.AQUA));
                // 候选体豁免 training_max_targets，但有自己的界（BACKLOG P1-6 第 13 条）：
                // 不显示出来，玩家分不清"还差多少练满"和"已经到顶了"。
                //
                // ⚠️ 这一行必须放在**本分支内**：本分支末尾有 `return`，
                // 而第一版把它加在了那个 return 之后的分支里 —— 编译器直接报
                // "条件始终为 false"（2026-09-26 由作者发现）。加工具提示这类"顺手的改动"
                // 特别容易掉进提前返回后面，因为编译器不一定报，而这一处恰好报了。
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.candidate_targets",
                                data.trainedTargets().size() + data.trainedEntities().size(),
                                FocalDecayConfig.CANDIDATE_MAX_TARGETS.get())
                        .withStyle(ChatFormatting.GRAY));
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.candidate_hint")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            return;
        }
        if (ObserverModelData.TYPE_GUIDED.equals(data.type())) {
            if (data.concept().isEmpty()) {
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.guided_invalid")
                        .withStyle(ChatFormatting.DARK_RED));
            } else {
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.guided_concept",
                                GuidedConcept.displayName(data.concept()),
                                Math.round(data.stabilityStrength() * 100))
                        .withStyle(ChatFormatting.AQUA));
                // 解锁档：q 的四个门槛各给一个新动词（DESIGN.md §13.10），
                // 所以工具提示要能一眼看出"这个模型现在能做什么"，而不是一个百分比。
                tooltipComponents.add(Component.translatable("tooltip.focal_decay.guided_unlock",
                                GuidedConcept.unlockFor(data.stabilityStrength()).displayName())
                        .withStyle(ChatFormatting.DARK_AQUA));
            }
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
            // 走到这里的一定不是候选体（候选体在那个分支末尾就 return 了），所以不需要再判类型。
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
