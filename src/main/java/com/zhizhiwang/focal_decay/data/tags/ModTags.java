package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
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
        public static final TagKey<Block> ANCHOR_PROTOTYPE_IMMUNE =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "anchor_prototype_immune"));
    }

    public static class EntityTypes {
        public static final TagKey<EntityType<?>> ENTITY_MUTATION_POOL_PASSIVE =
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "entity_mutation_pool_passive"));
        public static final TagKey<EntityType<?>> ENTITY_MUTATION_POOL_NEUTRAL =
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "entity_mutation_pool_neutral"));
        public static final TagKey<EntityType<?>> ENTITY_MUTATION_POOL_HOSTILE =
                TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "entity_mutation_pool_hostile"));
    }

    public static class Biomes {
        public static final TagKey<Biome> HAS_END_THRONE =
                TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "has_structure/end_throne"));
    }
}
