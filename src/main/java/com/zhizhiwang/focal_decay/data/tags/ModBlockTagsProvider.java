package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
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
        // 全局突变池：石头系 + 自然/建筑/下界/末地等多样方块，
        // 让"失焦"转换看起来明显而不是只有石头变体
        tag(ModTags.Blocks.GLOBAL_MUTATION_POOL)
                .addTag(BlockTags.STONE_ORE_REPLACEABLES)
                .add(
                        // 石头衍生
                        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                        Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                        Blocks.CHISELED_STONE_BRICKS,
                        Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                        Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES,
                        Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF,
                        Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
                        // 泥土/地表
                        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
                        Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.MUD,
                        Blocks.PACKED_MUD, Blocks.MUD_BRICKS, Blocks.MOSS_BLOCK,
                        Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
                        // 雪/冰
                        Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
                        // 木材
                        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
                        Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.CHERRY_LOG, Blocks.MANGROVE_LOG,
                        Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
                        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.CHERRY_PLANKS, Blocks.MANGROVE_PLANKS,
                        Blocks.BOOKSHELF, Blocks.HAY_BLOCK, Blocks.MELON, Blocks.PUMPKIN,
                        // 矿物与水晶
                        Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE,
                        Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.EMERALD_ORE, Blocks.DIAMOND_ORE,
                        Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS,
                        Blocks.AMETHYST_BLOCK,
                        // 下界
                        Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.POLISHED_BASALT,
                        Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
                        Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK, Blocks.GLOWSTONE,
                        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
                        // 末地与海洋
                        Blocks.END_STONE, Blocks.END_STONE_BRICKS,
                        Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN,
                        // 其他
                        Blocks.BONE_BLOCK, Blocks.SCULK, Blocks.TERRACOTTA
                );
    }
}
