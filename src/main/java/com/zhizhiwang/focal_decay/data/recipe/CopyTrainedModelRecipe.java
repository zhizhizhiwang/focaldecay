package com.zhizhiwang.focal_decay.data.recipe;

import com.mojang.serialization.MapCodec;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 模型复制配方（设计大纲 §4.4）：已训练模型 + 空白模型 → 2 份副本（保留训练数据）。
 * <p>
 * <b>复制会损耗代数</b>（2026-09-11 加入）：OBSR-EX 的参数是硬件写死的，复制不是拷贝文件而是重铸，
 * 每次重铸丢失一部分分类精度。因此副本带 {@code copies} 计数，半径按代数递减，
 * 且达到 {@code total_stability_max_copies} 后不再可复制——这条上限才是防止"无限铺满基地"的关键
 * （复制<b>不消耗</b>原件，没有上限就会无限增殖）。
 * <p>
 * 用副本合成的 OBSR-3 需要更多训练量，代价随代数递增，见
 * {@link com.zhizhiwang.focal_decay.item.ObserverModelItem#requiredCandidatePoints}。
 */
public class CopyTrainedModelRecipe extends CustomRecipe {

    public CopyTrainedModelRecipe(CraftingBookCategory category) {
        super(category);
    }

    private static boolean isTrained(ItemStack stack) {
        // 仅语义锁定/引导/已激活完全稳定模型可复制（未激活完全稳定模型不可复制）
        return stack.is(ModItems.SEMANTIC_LOCK_MODEL.get())
                || stack.is(ModItems.GUIDED_MUTATION_MODEL.get())
                || stack.is(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get());
    }

    /** 该模型还能再复制一代吗？ */
    private static boolean canCopyFurther(ItemStack stack) {
        ObserverModelData data = ObserverModelItem.getData(stack);
        return data == null || data.copies() < FocalDecayConfig.TOTAL_STABILITY_MAX_COPIES.get();
    }

    /**
     * 复制时副本应带的代数。
     * <p>
     * 正常情况下物品自带数据（见 {@code ModItems}），但旧存档里的物品、或其他途径造出的裸物品
     * 可能没有组件。这种情况按"原件（0）"处理并补上一份数据——否则复制出来的东西既没有组件、
     * 也就没有 {@code copies}，玩家看到的就是"复制了但毫无变化"。
     */
    private static ObserverModelData dataForCopy(ItemStack stack) {
        ObserverModelData data = ObserverModelItem.getData(stack);
        if (data == null) {
            ObserverModelData fallback = stack.is(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get())
                    ? ObserverModelData.activatedTotalStability()
                    : ObserverModelData.blank();
            return fallback.nextCopy();
        }
        return data.nextCopy();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        boolean trained = false;
        boolean blank = false;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isTrained(stack)) {
                if (trained || !canCopyFurther(stack)) {
                    return false;
                }
                trained = true;
            } else if (stack.is(ModItems.OBSERVER_MODEL_BLANK.get())) {
                if (blank) {
                    return false;
                }
                blank = true;
            } else {
                return false;
            }
        }
        return trained && blank;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (isTrained(stack)) {
                ItemStack copy = stack.copy();
                copy.setCount(2);
                // 代数 +1：副本半径递减，且用于合成 OBSR-3 时训练量要求更高。
                // dataForCopy 会在物品没有组件时补上默认数据，避免"复制了却没有 copies"。
                ObserverModelItem.setData(copy, dataForCopy(stack));
                return copy;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<CopyTrainedModelRecipe> getSerializer() {
        return (RecipeSerializer<CopyTrainedModelRecipe>) ModRecipeSerializers.COPY_TRAINED_MODEL.get();
    }

    public static class Serializer implements RecipeSerializer<CopyTrainedModelRecipe> {
        private static final MapCodec<CopyTrainedModelRecipe> CODEC =
                MapCodec.unit(() -> new CopyTrainedModelRecipe(CraftingBookCategory.MISC));

        @Override
        public MapCodec<CopyTrainedModelRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CopyTrainedModelRecipe> streamCodec() {
            return StreamCodec.of(
                    (buf, recipe) -> {
                    },
                    buf -> new CopyTrainedModelRecipe(CraftingBookCategory.MISC)
            );
        }
    }
}
