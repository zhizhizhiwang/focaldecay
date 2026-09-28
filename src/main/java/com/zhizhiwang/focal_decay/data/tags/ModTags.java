package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
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
        // ---- 突变系统（2026-09-15 重写：池 / 形态类 / 源，全部由数据包标签决定）----

        /** 大池：以 {@code wild_chance} 的概率整枝命中，用来保证长尾随机性（旧 global_mutation_pool）。 */
        public static final TagKey<Block> MUTATION_POOL_WILD = mutationPool("wild");
        public static final TagKey<Block> MUTATION_POOL_WILD_NETHER = mutationPool("wild_nether");
        public static final TagKey<Block> MUTATION_POOL_WILD_END = mutationPool("wild_end");

        /** 完全豁免：既不做突变源，也不会被抽成目标（旧 conversion_blacklist）。 */
        public static final TagKey<Block> MUTATION_IMMUNE =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "mutation_immune"));

        /** 额外源：会失焦，但永远不会被抽成目标（单向，不会造成冻结方块）。 */
        public static final TagKey<Block> MUTATION_SOURCE_EXTRA =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "mutation_source_extra"));

        /** 稳定锚免疫：观测者核心不可被转换。 */
        public static final TagKey<Block> ANCHOR_PROTOTYPE_IMMUNE =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "anchor_prototype_immune"));

        // ---- tier（获得门槛，2026-09-28，见 Tiers 与 DESIGN.md §13.9）----
        /**
         * tier 覆盖标签：{@code focal_decay:tier_override/t<0..3>}。
         * 命中即以该档为准，不再套用工具门槛与压缩 +1。T4 没有覆盖标签——它只由压缩规则产生。
         */
        public static TagKey<Block> tierOverride(int tier) {
            return BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "tier_override/t" + tier));
        }

        /** 压缩形态的补充名单：{@code c:storage_blocks/*} 没覆盖到的压缩块（石英族、紫水晶块）。 */
        public static final TagKey<Block> COMPRESSED_EXTRA =
                BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "compressed_extra"));

        /** 突变池标签：{@code focal_decay:mutation_pool/<name>}。 */
        public static TagKey<Block> mutationPool(String name) {
            return BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "mutation_pool/" + name));
        }

        /** 形态类标签：{@code focal_decay:shape_class/<name>}。目标必须与源同形态类。 */
        public static TagKey<Block> shapeClass(String name) {
            return BlockTags.create(ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "shape_class/" + name));
        }

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

        /** 维度对应的大池：下界/末地用专属池，其余维度（含未知模组维度）回退主世界大池。 */
        public static TagKey<Block> poolForDimension(ResourceKey<Level> dimension) {
            if (dimension == Level.NETHER) {
                return MUTATION_POOL_WILD_NETHER;
            }
            if (dimension == Level.END) {
                return MUTATION_POOL_WILD_END;
            }
            return MUTATION_POOL_WILD;
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
