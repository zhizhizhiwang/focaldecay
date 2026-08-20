package com.zhizhiwang.focal_decay.data;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import com.zhizhiwang.focal_decay.structure.ThroneStructurePlacement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 世界生成数据：生成 worldgen/structure/end_throne.json 与 worldgen/structure_set/end_throne.json。
 * 结构集用 random_spread（大间距），但唯一性由 ThroneStructure.findGenerationPoint 保证。
 */
public class ModWorldGenProvider extends DatapackBuiltinEntriesProvider {
    public static final ResourceKey<Structure> END_THRONE =
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "end_throne"));
    public static final ResourceKey<StructureSet> END_THRONE_SET =
            ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "end_throne"));

    private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
            .add(Registries.STRUCTURE, ModWorldGenProvider::bootstrapStructures)
            .add(Registries.STRUCTURE_SET, ModWorldGenProvider::bootstrapStructureSets);

    public ModWorldGenProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, BUILDER, Set.of(FocalDecay.MODID));
    }

    private static void bootstrapStructures(BootstrapContext<Structure> context) {
        HolderGetter<Biome> biomes = context.lookup(Registries.BIOME);
        HolderSet<Biome> holders = biomes.getOrThrow(ModTags.Biomes.HAS_END_THRONE);
        context.register(END_THRONE, new ThroneStructure(new Structure.StructureSettings(holders)));
    }

    private static void bootstrapStructureSets(BootstrapContext<StructureSet> context) {
        HolderGetter<Structure> structures = context.lookup(Registries.STRUCTURE);
        context.register(END_THRONE_SET, new StructureSet(
                structures.getOrThrow(END_THRONE),
                new ThroneStructurePlacement(20, 10, RandomSpreadType.LINEAR, 19851206)));
    }
}
