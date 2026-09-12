package com.zhizhiwang.focal_decay.data;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.data.recipe.ModRecipeProvider;
import com.zhizhiwang.focal_decay.data.tags.ModBlockTagsProvider;
import com.zhizhiwang.focal_decay.data.tags.ModBiomeTagsProvider;
import com.zhizhiwang.focal_decay.data.tags.ModEntityTypeTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.advancements.AdvancementProvider;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ModDataGenerator {

    private ModDataGenerator() {
    }

    /** 在 mod 构造器中调用，注册数据生成监听器。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModDataGenerator::gatherData);
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        generator.addProvider(true, new ModBlockTagsProvider(packOutput, lookupProvider, existingFileHelper));
        generator.addProvider(true, new ModBiomeTagsProvider(packOutput, lookupProvider, existingFileHelper));
        generator.addProvider(true, new ModEntityTypeTagsProvider(packOutput, lookupProvider, existingFileHelper));
        generator.addProvider(true, new ModRecipeProvider(packOutput, lookupProvider));
        generator.addProvider(true, new ModWorldGenProvider(packOutput, lookupProvider));

        // 手册发放链路（设计大纲 §14）：隐藏 advancement 驱动
        // data/focal_decay/loot_table/grant_observer_manual.json（战利品表为手写资源，
        // 因为 LootTableProvider 会用 vanilla codec 归一化写盘，必然丢掉 neoforge:conditions）
        generator.addProvider(true, new AdvancementProvider(packOutput, lookupProvider, List.of(new ModAdvancementProvider())));
    }
}
