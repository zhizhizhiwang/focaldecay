package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.EntityTypeTagsProvider;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 实体突变池标签（设计大纲 §4.1.2）。
 * 三阶段分别启用：一阶段被动 / 二阶段加入中立 / 三阶段加入敌对。
 * 生成时即可通过标签包含关系体现阶段递进。
 */
public class ModEntityTypeTagsProvider extends EntityTypeTagsProvider {

    public ModEntityTypeTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, FocalDecay.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // ---- 一阶段：被动生物 ----
        tag(ModTags.EntityTypes.ENTITY_MUTATION_POOL_PASSIVE)
                .add(EntityType.CHICKEN)
                .add(EntityType.COW)
                .add(EntityType.PIG)
                .add(EntityType.SHEEP)
                .add(EntityType.RABBIT)
                .add(EntityType.TURTLE)
                .add(EntityType.PANDA)
                .add(EntityType.FOX)
                .add(EntityType.WOLF)
                .add(EntityType.CAT)
                .add(EntityType.HORSE)
                .add(EntityType.DONKEY)
                .add(EntityType.MULE)
                .add(EntityType.LLAMA)
                .add(EntityType.AXOLOTL)
                .add(EntityType.BEE)
                .add(EntityType.VILLAGER);

        // ---- 二阶段：中立生物 ----
        tag(ModTags.EntityTypes.ENTITY_MUTATION_POOL_NEUTRAL)
                .add(EntityType.ZOMBIFIED_PIGLIN)
                .add(EntityType.ENDERMAN)
                .add(EntityType.SPIDER)
                .add(EntityType.CAVE_SPIDER)
                .add(EntityType.BEE)
                .add(EntityType.GOAT)
                .add(EntityType.IRON_GOLEM);

        // ---- 三阶段：敌对生物 ----
        tag(ModTags.EntityTypes.ENTITY_MUTATION_POOL_HOSTILE)
                .add(EntityType.ZOMBIE)
                .add(EntityType.SKELETON)
                .add(EntityType.CREEPER)
                .add(EntityType.SPIDER)
                .add(EntityType.CAVE_SPIDER)
                .add(EntityType.WITCH)
                .add(EntityType.WITHER_SKELETON)
                .add(EntityType.HUSK)
                .add(EntityType.DROWNED)
                .add(EntityType.STRAY)
                .add(EntityType.PHANTOM)
                .add(EntityType.SLIME);
    }
}
