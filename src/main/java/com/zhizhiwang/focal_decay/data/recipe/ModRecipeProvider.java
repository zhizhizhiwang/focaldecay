package com.zhizhiwang.focal_decay.data.recipe;

import com.zhizhiwang.focal_decay.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.data.recipes.SpecialRecipeBuilder;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;

public class ModRecipeProvider extends RecipeProvider {

    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        // ---- 稳定锚原型机：R F R / F E F / R F R (R=红石粉 F=铁块 E=末影之眼) ----
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.ANCHOR_PROTOTYPE.get())
                .pattern("RFR")
                .pattern("FEF")
                .pattern("RFR")
                .define('R', Items.REDSTONE)
                .define('F', Items.IRON_BLOCK)
                .define('E', Items.ENDER_EYE)
                .unlockedBy("has_ender_eye", has(Items.ENDER_EYE))
                .save(recipeOutput);

        // ---- 空白观测模型：书 + 金锭 + 青金石 + 铜锭（任意形状） ----
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.OBSERVER_MODEL_BLANK.get())
                .requires(Items.BOOK)
                .requires(Items.GOLD_INGOT)
                .requires(Items.LAPIS_LAZULI)
                .requires(Items.COPPER_INGOT)
                .unlockedBy("has_book", has(Items.BOOK))
                .save(recipeOutput);

        // ---- 重建的观测协议：7 碎片无序合成（自定义配方类型） ----
        SpecialRecipeBuilder.special(
                (net.minecraft.world.item.crafting.CraftingBookCategory category) ->
                        new RebuildObserverProtocolRecipe(category))
                .save(recipeOutput, "focal_decay:rebuild_observer_protocol");

        // ---- 训练模型复制：已训练模型 + 空白模型 → 2 份（保留训练数据） ----
        SpecialRecipeBuilder.special(
                (net.minecraft.world.item.crafting.CraftingBookCategory category) ->
                        new CopyTrainedModelRecipe(category))
                .save(recipeOutput, "focal_decay:copy_trained_model");
    }
}
