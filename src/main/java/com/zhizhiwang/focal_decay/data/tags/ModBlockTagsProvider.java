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
        tag(ModTags.Blocks.ANCHOR_PROTOTYPE_IMMUNE)
                .add(ModBlocks.OBSERVER_CORE.get())
                .add(ModBlocks.THRONE_BLOCK.get());
        // 转换黑名单：同样排除核心块
        tag(ModTags.Blocks.CONVERSION_BLACKLIST)
                .add(ModBlocks.OBSERVER_CORE.get())
                .add(ModBlocks.THRONE_BLOCK.get());
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
        // 引导模型概念标签（方案 A，2026-08-21）：训练目标按覆盖率指认概念，概念邻域=标签成员
        tag(ModTags.Blocks.CONCEPT_WOOD)
                .addTag(BlockTags.LOGS_THAT_BURN)
                .addTag(BlockTags.PLANKS)
                .add(Blocks.BAMBOO_BLOCK, Blocks.STRIPPED_BAMBOO_BLOCK);
        tag(ModTags.Blocks.CONCEPT_ORE)
                .add(
                        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
                        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
                        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
                        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
                        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
                        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
                        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
                        Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE,
                        Blocks.ANCIENT_DEBRIS,
                        Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_GOLD_BLOCK,
                        Blocks.COAL_BLOCK, Blocks.IRON_BLOCK, Blocks.COPPER_BLOCK, Blocks.GOLD_BLOCK,
                        Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.EMERALD_BLOCK,
                        Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.QUARTZ_BLOCK,
                        Blocks.AMETHYST_BLOCK
                );
        tag(ModTags.Blocks.CONCEPT_STONE)
                .addTag(BlockTags.STONE_ORE_REPLACEABLES)
                .add(
                        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                        Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                        Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE,
                        Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                        Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES,
                        Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF,
                        Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
                        Blocks.SMOOTH_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE,
                        Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
                        Blocks.BASALT, Blocks.POLISHED_BASALT,
                        Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
                        Blocks.END_STONE, Blocks.END_STONE_BRICKS
                );
        tag(ModTags.Blocks.CONCEPT_GLASS)
                .add(Blocks.GLASS, Blocks.TINTED_GLASS)
                .add(
                        Blocks.WHITE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
                        Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS,
                        Blocks.PINK_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS,
                        Blocks.CYAN_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS,
                        Blocks.BROWN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.RED_STAINED_GLASS,
                        Blocks.BLACK_STAINED_GLASS
                )
                .add(Blocks.GLASS_PANE)
                .add(
                        Blocks.WHITE_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE, Blocks.MAGENTA_STAINED_GLASS_PANE,
                        Blocks.LIGHT_BLUE_STAINED_GLASS_PANE, Blocks.YELLOW_STAINED_GLASS_PANE, Blocks.LIME_STAINED_GLASS_PANE,
                        Blocks.PINK_STAINED_GLASS_PANE, Blocks.GRAY_STAINED_GLASS_PANE, Blocks.LIGHT_GRAY_STAINED_GLASS_PANE,
                        Blocks.CYAN_STAINED_GLASS_PANE, Blocks.PURPLE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS_PANE,
                        Blocks.BROWN_STAINED_GLASS_PANE, Blocks.GREEN_STAINED_GLASS_PANE, Blocks.RED_STAINED_GLASS_PANE,
                        Blocks.BLACK_STAINED_GLASS_PANE
                );
        tag(ModTags.Blocks.CONCEPT_TERRACOTTA)
                .add(Blocks.TERRACOTTA)
                .add(
                        Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                        Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
                        Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                        Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
                        Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
                        Blocks.BLACK_TERRACOTTA
                )
                .add(
                        Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.MAGENTA_GLAZED_TERRACOTTA,
                        Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA,
                        Blocks.PINK_GLAZED_TERRACOTTA, Blocks.GRAY_GLAZED_TERRACOTTA, Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA,
                        Blocks.CYAN_GLAZED_TERRACOTTA, Blocks.PURPLE_GLAZED_TERRACOTTA, Blocks.BLUE_GLAZED_TERRACOTTA,
                        Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA, Blocks.RED_GLAZED_TERRACOTTA,
                        Blocks.BLACK_GLAZED_TERRACOTTA
                );
        tag(ModTags.Blocks.CONCEPT_WOOL)
                .addTag(BlockTags.WOOL)
                .addTag(BlockTags.WOOL_CARPETS);
    }
}
