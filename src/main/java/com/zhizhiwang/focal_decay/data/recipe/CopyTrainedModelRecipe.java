package com.zhizhiwang.focal_decay.data.recipe;

import com.mojang.serialization.MapCodec;
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
 * 模型复制配方（设计大纲 §4.4）：已训练模型 + 空白模型 → 2 份已训练模型（保留训练数据）。
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
                if (trained) {
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
