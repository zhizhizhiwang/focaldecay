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

        // ---- 生物稳定模型：G R G / L O L / G A G (G=玻璃 R=红色染料 L=拴绳 O=原型 A=紫水晶碎片) ----
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.BIO_STABILIZER_MODEL.get())
                .pattern("GRG")
                .pattern("LOL")
                .pattern("GAG")
                .define('G', Items.GLASS)
                .define('R', Items.RED_DYE)
                .define('L', Items.LEAD)
                .define('O', ModItems.OBSERVER_MODEL_BLANK.get())
                .define('A', Items.AMETHYST_SHARD)
                .unlockedBy("has_observer_model_blank", has(ModItems.OBSERVER_MODEL_BLANK.get()))
                .save(recipeOutput);

        // ---- 训练终端：C R C / E B E / C O C (C=铜块 R=红石粉 E=末影之眼 B=书 O=原型) ----
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.TRAINING_TERMINAL.get())
                .pattern("CRC")
                .pattern("EBE")
                .pattern("COC")
                .define('C', Items.COPPER_BLOCK)
                .define('R', Items.REDSTONE)
                .define('E', Items.ENDER_EYE)
                .define('B', Items.BOOK)
                .define('O', ModItems.OBSERVER_MODEL_BLANK.get())
                .unlockedBy("has_observer_model_blank", has(ModItems.OBSERVER_MODEL_BLANK.get()))
                .save(recipeOutput);

        // ---- 候选观测者 OBSR-3：O E O / E X E / O S O
        //      (O=原型 E=末影之眼 X=已激活的 OBSR-EX S=下界之星) ----
        // 以"工作中的 OBSR-EX"为材料，呼应主线：新观测者由上一迭代留下的完备分类器派生。
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.OBSERVER_MODEL_CANDIDATE.get())
                .pattern("OEO")
                .pattern("EXE")
                .pattern("OSO")
                .define('O', ModItems.OBSERVER_MODEL_BLANK.get())
                .define('E', Items.ENDER_EYE)
                .define('X', ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get())
                .define('S', Items.NETHER_STAR)
                .unlockedBy("has_total_stability_model_activated",
                        has(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get()))
                .save(recipeOutput);

        // ---- 空白观测模型：书 + 金锭 + 青金石 + 铜锭（任意形状） ----
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.OBSERVER_MODEL_BLANK.get())
                .requires(Items.BOOK)
                .requires(Items.GOLD_INGOT)
                .requires(Items.LAPIS_LAZULI)
                .requires(Items.COPPER_INGOT)
                .unlockedBy("has_book", has(Items.BOOK))
                .save(recipeOutput);

        // ---- 训练模型复制：已训练模型 + 空白模型 → 2 份（保留训练数据） ----
        SpecialRecipeBuilder.special(
                (net.minecraft.world.item.crafting.CraftingBookCategory category) ->
                        new CopyTrainedModelRecipe(category))
                .save(recipeOutput, "focal_decay:copy_trained_model");

        // ---- 语义知识注入：候选观测者 + 任一碎片 → 进度 +X ----
        SpecialRecipeBuilder.special(
                (net.minecraft.world.item.crafting.CraftingBookCategory category) ->
                        new FeedSemanticFragmentRecipe(category))
                .save(recipeOutput, "focal_decay:feed_semantic_fragment");
    }
}
