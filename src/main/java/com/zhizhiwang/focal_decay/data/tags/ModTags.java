package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

import java.util.List;

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

        // 引导模型概念标签（方案 A，2026-08-21）：训练完成时按覆盖率指认概念
        public static final TagKey<Block> CONCEPT_WOOD =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "concept/wood"));
        public static final TagKey<Block> CONCEPT_ORE =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "concept/ore"));
        public static final TagKey<Block> CONCEPT_STONE =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "concept/stone"));
        public static final TagKey<Block> CONCEPT_GLASS =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "concept/glass"));
        public static final TagKey<Block> CONCEPT_TERRACOTTA =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "concept/terracotta"));
        public static final TagKey<Block> CONCEPT_WOOL =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "concept/wool"));

        private static final List<TagKey<Block>> CURATED_CONCEPTS = List.of(
                CONCEPT_WOOD, CONCEPT_ORE, CONCEPT_STONE, CONCEPT_GLASS, CONCEPT_TERRACOTTA, CONCEPT_WOOL);

        /** 全部策展概念标签（确定性顺序：概念解析的候选顺序）。 */
        public static List<TagKey<Block>> curatedConcepts() {
            return CURATED_CONCEPTS;
        }

        /** 该标签是否为策展概念标签。 */
        public static boolean isCurated(TagKey<Block> tag) {
            return CURATED_CONCEPTS.contains(tag);
        }
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
