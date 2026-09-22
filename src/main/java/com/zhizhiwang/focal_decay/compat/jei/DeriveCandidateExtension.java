package com.zhizhiwang.focal_decay.compat.jei;

import com.zhizhiwang.focal_decay.data.recipe.DeriveCandidateRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

/**
 * 让 JEI 把 OBSR-3 的派生配方当成一张普通工作台配方来展示与检索。
 * <p>
 * <b>为什么必须显式登记</b>：{@link DeriveCandidateRecipe} 是特殊配方（{@code CustomRecipe}，
 * {@code isSpecial()} 为 true），而 JEI 自带的工作台扩展
 * （{@code mezz.jei.library.plugins.vanilla.crafting.CraftingCategoryExtension#isHandled}）
 * 的条件就是 {@code !recipe.isSpecial()}——特殊配方一律<b>不接手</b>，既不展示也不建索引。
 * 结果就是对 OBSR-3 按 R、对已激活的 OBSR-EX 按 U 都查不到任何东西（2026-09-17 修）。
 * <p>
 * 注册之后，JEI 用这里给出的槽位建索引：产物在输出槽（对它按 R 能查回来），
 * 九个材料铺在输入槽（对核心按 U 能查过来）。这条路径走的是 JEI 的原生工作台类别，
 * 因此不依赖我们自己的自定义类别，也就不存在"自定义类别没被索引到"的风险。
 */
public final class DeriveCandidateExtension implements ICraftingCategoryExtension<DeriveCandidateRecipe> {

    /** 配方占满 3x3：JEI 用它决定网格的宽高（否则会按无序配方画成一长条）。 */
    private static final int GRID = 3;

    @Override
    public void setRecipe(RecipeHolder<DeriveCandidateRecipe> recipeHolder, IRecipeLayoutBuilder builder,
                          ICraftingGridHelper craftingGridHelper, IFocusGroup focuses) {
        DeriveCandidateRecipe recipe = recipeHolder.value();
        craftingGridHelper.createAndSetOutputs(builder, List.of(recipe.displayResult()));
        craftingGridHelper.createAndSetIngredients(builder, recipe.getIngredients(), GRID, GRID);
    }

    @Override
    public int getWidth(RecipeHolder<DeriveCandidateRecipe> recipeHolder) {
        return GRID;
    }

    @Override
    public int getHeight(RecipeHolder<DeriveCandidateRecipe> recipeHolder) {
        return GRID;
    }
}
