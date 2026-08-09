package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

public class ModBlockTagsProvider extends BlockTagsProvider {

    public ModBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, FocalDecay.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // 稳定锚免疫：观测者核心不可被转换
        tag(ModTags.Blocks.STABLE_ANCHOR_IMMUNE)
                .add(ModBlocks.OBSERVER_CORE.get());
        // 转换黑名单：同样排除核心块
        tag(ModTags.Blocks.CONVERSION_BLACKLIST)
                .add(ModBlocks.OBSERVER_CORE.get());
        // 全局突变池：从原版"可被矿石替换"标签起步，后续由逻辑代码补充完整集合
        tag(ModTags.Blocks.GLOBAL_MUTATION_POOL)
                .addTag(BlockTags.STONE_ORE_REPLACEABLES);
    }
}
