package com.zhizhiwang.focal_decay.data.recipe;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, FocalDecay.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> COPY_TRAINED_MODEL =
            RECIPE_SERIALIZERS.register("crafting_special_copytrainedmodel",
                    CopyTrainedModelRecipe.Serializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> FEED_SEMANTIC_FRAGMENT =
            RECIPE_SERIALIZERS.register("crafting_special_feedfragment",
                    FeedSemanticFragmentRecipe.Serializer::new);

    /** OBSR-3 派生：要把所用 OBSR-EX 的复制代数抄进结果，所以必须是特殊配方。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> DERIVE_CANDIDATE =
            RECIPE_SERIALIZERS.register("crafting_special_derivecandidate",
                    DeriveCandidateRecipe.Serializer::new);

    private ModRecipeSerializers() {
    }
}
