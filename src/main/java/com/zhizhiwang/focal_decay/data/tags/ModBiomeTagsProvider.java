package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/** 末地王座的可生成生物群系标签（末地全域，王座实际落在虚空带 the_end）。 */
public class ModBiomeTagsProvider extends TagsProvider<Biome> {
    public ModBiomeTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                                ExistingFileHelper existingFileHelper) {
        super(output, Registries.BIOME, lookupProvider, FocalDecay.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModTags.Biomes.HAS_END_THRONE)
                .add(Biomes.THE_END)
                .add(Biomes.SMALL_END_ISLANDS)
                .add(Biomes.END_BARRENS)
                .add(Biomes.END_MIDLANDS)
                .add(Biomes.END_HIGHLANDS);
    }
}
