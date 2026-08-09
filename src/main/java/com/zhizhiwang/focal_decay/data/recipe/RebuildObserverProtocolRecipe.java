package com.zhizhiwang.focal_decay.data.recipe;

import com.mojang.serialization.MapCodec;
import com.zhizhiwang.focal_decay.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 重建的观测协议：7 个语义碎片无序合成（设计大纲 §2.2）。
 * 使用原版 SimpleCraftingRecipeSerializer 序列化（无额外 NBT 字段）。
 */
public class RebuildObserverProtocolRecipe extends CustomRecipe {

    public RebuildObserverProtocolRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        int fragments = 0;
        boolean hasExtra = false;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isFragment(stack)) {
                fragments++;
            } else {
                hasExtra = true;
            }
        }
        // 需要恰好 7 个不同碎片且无其他物品
        return !hasExtra && fragments == 7 && distinctFragments(input) == 7;
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

    /** 统计出现的不同碎片种类数（1-7）。 */
    private static int distinctFragments(CraftingInput input) {
        int bits = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ModItems.FRAGMENT_ROSE.get())) bits |= 1 << 0;
            else if (stack.is(ModItems.FRAGMENT_THRONE.get())) bits |= 1 << 1;
            else if (stack.is(ModItems.FRAGMENT_SEMANTIC.get())) bits |= 1 << 2;
            else if (stack.is(ModItems.FRAGMENT_42MS.get())) bits |= 1 << 3;
            else if (stack.is(ModItems.FRAGMENT_CRYSTAL.get())) bits |= 1 << 4;
            else if (stack.is(ModItems.FRAGMENT_AARON.get())) bits |= 1 << 5;
            else if (stack.is(ModItems.FRAGMENT_CHENG.get())) bits |= 1 << 6;
        }
        return Integer.bitCount(bits);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return new ItemStack(ModItems.REBUILT_OBSERVER_PROTOCOL.get());
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 7;
    }

    @Override
    public RecipeSerializer<RebuildObserverProtocolRecipe> getSerializer() {
        return (RecipeSerializer<RebuildObserverProtocolRecipe>) ModRecipeSerializers.REBUILD_OBSERVER.get();
    }

    public static class Serializer implements RecipeSerializer<RebuildObserverProtocolRecipe> {
        private static final MapCodec<RebuildObserverProtocolRecipe> CODEC =
                MapCodec.unit(() -> new RebuildObserverProtocolRecipe(CraftingBookCategory.MISC));

        @Override
        public MapCodec<RebuildObserverProtocolRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, RebuildObserverProtocolRecipe> streamCodec() {
            return StreamCodec.of(
                    (buf, recipe) -> {
                    },
                    buf -> new RebuildObserverProtocolRecipe(CraftingBookCategory.MISC)
            );
        }
    }
}
