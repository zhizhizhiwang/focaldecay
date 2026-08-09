package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/**
 * 模组使用的全部标签（设计大纲 §13）。
 */
public final class ModTags {

    private ModTags() {
    }

    public static class Blocks {
        public static final TagKey<Block> GLOBAL_MUTATION_POOL =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "global_mutation_pool"));
        public static final TagKey<Block> CONVERSION_BLACKLIST =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "conversion_blacklist"));
        public static final TagKey<Block> STABLE_ANCHOR_IMMUNE =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "stable_anchor_immune"));
    }

    public static class EntityTypes {
        public static final TagKey<EntityType<?>> ENTITY_MUTATION_POOL_PASSIVE =
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "entity_mutation_pool_passive"));
        public static final TagKey<EntityType<?>> ENTITY_MUTATION_POOL_NEUTRAL =
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "entity_mutation_pool_neutral"));
        public static final TagKey<EntityType<?>> ENTITY_MUTATION_POOL_HOSTILE =
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "entity_mutation_pool_hostile"));
    }
}
