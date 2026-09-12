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
 * 语义知识注入配方（2026-08-21）：候选观测者模型 + 任一语义碎片 → 候选模型（进度 +X，碎片消耗）。
 */
public class FeedSemanticFragmentRecipe extends CustomRecipe {

    public FeedSemanticFragmentRecipe(CraftingBookCategory category) {
        super(category);
    }

    private static boolean isFragment(ItemStack stack) {
        return stack.is(ModItems.FRAGMENT_ROSE.get())
                || stack.is(ModItems.FRAGMENT_THRONE.get())
                || stack.is(ModItems.FRAGMENT_SEMANTIC.get())
                || stack.is(ModItems.FRAGMENT_42MS.get())
                || stack.is(ModItems.FRAGMENT_CRYSTAL.get())
                || stack.is(ModItems.FRAGMENT_AARON.get())
                || stack.is(ModItems.FRAGMENT_CHENG.get());
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        boolean candidate = false;
        boolean fragment = false;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ModItems.OBSERVER_MODEL_CANDIDATE.get())) {
                if (candidate) {
                    return false;
                }
                candidate = true;
            } else if (isFragment(stack)) {
                if (fragment) {
                    return false;
                }
                fragment = true;
            } else {
                return false;
            }
        }
        return candidate && fragment;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.is(ModItems.OBSERVER_MODEL_CANDIDATE.get())) {
                continue;
            }
            ItemStack copy = stack.copy();
            copy.setCount(1);
            ObserverModelData data = ObserverModelItem.getData(copy);
            if (data != null && ObserverModelData.TYPE_CANDIDATE.equals(data.type())) {
                ObserverModelItem.setData(copy, new ObserverModelData(
                        data.type(), data.trainedTargets(), data.trainedEntities(),
                        data.stabilityStrength(), data.concept(),
                        data.progress() + Math.max(0, FocalDecayConfig.CANDIDATE_FRAGMENT_POINTS.get()),
                        data.bioEnergy(), data.totalStability(), data.copies()));
            }
            return copy;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<FeedSemanticFragmentRecipe> getSerializer() {
        return (RecipeSerializer<FeedSemanticFragmentRecipe>) ModRecipeSerializers.FEED_SEMANTIC_FRAGMENT.get();
    }

    public static class Serializer implements RecipeSerializer<FeedSemanticFragmentRecipe> {
        private static final MapCodec<FeedSemanticFragmentRecipe> CODEC =
                MapCodec.unit(() -> new FeedSemanticFragmentRecipe(CraftingBookCategory.MISC));

        @Override
        public MapCodec<FeedSemanticFragmentRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, FeedSemanticFragmentRecipe> streamCodec() {
            return StreamCodec.of(
                    (buf, recipe) -> {
                    },
                    buf -> new FeedSemanticFragmentRecipe(CraftingBookCategory.MISC)
            );
        }
    }
}
