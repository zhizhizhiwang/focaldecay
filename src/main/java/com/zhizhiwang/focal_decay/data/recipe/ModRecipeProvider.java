package com.zhizhiwang.focal_decay.data.recipe;

import com.zhizhiwang.focal_decay.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.SpecialRecipeBuilder;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;

public class ModRecipeProvider extends RecipeProvider {

    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        // ---- 稳定锚：R F R / F E F / R F R (R=红石粉 F=铁块 E=突变控制器) ----
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.STABLE_ANCHOR.get())
                .pattern("RFR")
                .pattern("FEF")
                .pattern("RFR")
                .define('R', Items.REDSTONE)
                .define('F', Items.IRON_BLOCK)
                .define('E', ModItems.MUTATION_CONTROLLER.get())
                .unlockedBy("has_mutation_controller", has(ModItems.MUTATION_CONTROLLER.get()))
                .save(recipeOutput);

        // ---- 突变控制器：A B A / B S B / A B A (S=末影珍珠 B=书与笔 A=金锭) ----
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.MUTATION_CONTROLLER.get())
                .pattern("ABA")
                .pattern("BSB")
                .pattern("ABA")
                .define('A', Items.GOLD_INGOT)
                .define('B', Items.WRITABLE_BOOK)
                .define('S', Items.ENDER_PEARL)
                .unlockedBy("has_ender_pearl", has(Items.ENDER_PEARL))
                .save(recipeOutput);

        // ---- 重建的观测协议：7 碎片无序合成（自定义配方类型） ----
        SpecialRecipeBuilder.special(
                (net.minecraft.world.item.crafting.CraftingBookCategory category) ->
                        new RebuildObserverProtocolRecipe(category))
                .save(recipeOutput, "focal_decay:rebuild_observer_protocol");
    }
}
