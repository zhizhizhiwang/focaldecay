package com.zhizhiwang.focal_decay.data.tags;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 方块标签数据生成（2026-09-15 重构）。
 * <p>
 * <b>突变系统的全部配置都在这里生成</b>，运行时只读标签、不读本文件：
 * <ul>
 *   <li>{@code focal_decay:shape_class/*} —— 形态类。目标必须与源同形态类，
 *       所以"楼梯只变楼梯、半砖只变半砖"。非完整方块<b>只有</b>被登记进形态类才会参与突变。</li>
 *   <li>{@code focal_decay:mutation_pool/<名>} —— 语义池。一个方块可以进任意多个池，
 *       抽目标时取它所属全部池的并集；跨池的交叉归类（例如 {@code color/white} 把白色羊毛、
 *       白色混凝土、白色陶瓦放进同一个池）正是"多池并集"的用法。</li>
 *   <li>{@code focal_decay:mutation_pool/wild} —— 大池，按 {@code wild_chance} 概率整枝命中。</li>
 *   <li>{@code focal_decay:mutation_immune} —— 完全豁免；{@code mutation_source_extra} ——
 *       只出不进的额外源。</li>
 * </ul>
 * 数据包想改行为不需要碰代码：往这些标签里加内容即可（标签可以来自任意命名空间的数据包，
 * 只要放在 {@code data/focal_decay/tags/block/} 下）。
 */
public class ModBlockTagsProvider extends BlockTagsProvider {

    public ModBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, FocalDecay.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        shapeClasses();
        immunityAndSources();
        wildPools();
        semanticPools();
        shapePools();
        colorPools();
        concepts();
    }

    /** 玻璃板没有原版"panes"标签，只能列清单；形态类与池共用这份清单。 */
    private static final Block[] PANES = {
            Blocks.IRON_BARS, Blocks.GLASS_PANE,
            Blocks.WHITE_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE,
            Blocks.MAGENTA_STAINED_GLASS_PANE, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE,
            Blocks.YELLOW_STAINED_GLASS_PANE, Blocks.LIME_STAINED_GLASS_PANE,
            Blocks.PINK_STAINED_GLASS_PANE, Blocks.GRAY_STAINED_GLASS_PANE,
            Blocks.LIGHT_GRAY_STAINED_GLASS_PANE, Blocks.CYAN_STAINED_GLASS_PANE,
            Blocks.PURPLE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS_PANE,
            Blocks.BROWN_STAINED_GLASS_PANE, Blocks.GREEN_STAINED_GLASS_PANE,
            Blocks.RED_STAINED_GLASS_PANE, Blocks.BLACK_STAINED_GLASS_PANE
    };

    // ------------------------------------------------------------------
    // 形态类：几何上能互相替换的方块分组
    // ------------------------------------------------------------------

    /**
     * 形态类。
     * <p>
     * 非完整方块（楼梯/半砖/栅栏/墙/玻璃板/活板门/地毯）在旧实现里是<b>完全被排除</b>的，
     * 现在只要在这里登记过，就会在自己的形态类内部互相突变。
     * <p>
     * 门、床、高花这类<b>双格方块刻意不登记</b>：突变是逐坐标的纯函数，上下两半各自独立抽取
     * 就会抽出两种不同的门。要支持它们必须引入"锚半格"种子与配对写入。
     * 即使有数据包硬把它们塞进来，运行时的守卫也会剔除并打警告
     * （判定基于方块状态里的 {@code DoubleBlockHalf}/{@code BedPart} 属性，与具体方块类无关）。
     * <p>
     * 用原版标签而不是逐方块列清单：模组楼梯只要把自己加进 {@code #minecraft:stairs} 就自动生效。
     */
    private void shapeClasses() {
        tag(ModTags.Blocks.shapeClass("stairs")).addTag(BlockTags.STAIRS);
        tag(ModTags.Blocks.shapeClass("slabs")).addTag(BlockTags.SLABS);
        tag(ModTags.Blocks.shapeClass("fences")).addTag(BlockTags.FENCES);
        tag(ModTags.Blocks.shapeClass("fence_gates")).addTag(BlockTags.FENCE_GATES);
        tag(ModTags.Blocks.shapeClass("walls")).addTag(BlockTags.WALLS);
        tag(ModTags.Blocks.shapeClass("trapdoors")).addTag(BlockTags.TRAPDOORS);
        tag(ModTags.Blocks.shapeClass("carpets")).addTag(BlockTags.WOOL_CARPETS);
        tag(ModTags.Blocks.shapeClass("panes")).add(PANES);
    }

    // ------------------------------------------------------------------
    // 豁免 / 额外源
    // ------------------------------------------------------------------

    /**
     * 豁免与额外源。
     * <p>
     * 豁免 = 既不做源也不做目标。除了原来的两个核心方块，这里补上了"技术性/不可获取"的方块：
     * 它们本来在旧实现里是可以被失焦的（源门控只看"是不是完整方块"），
     * 但让世界突变出基岩、命令方块、传送门框架显然不是设计意图。
     * <p>
     * 额外源留空：默认配置下"进池即源"，用不到它；需要"会坏掉但永远不会被抽到"的方块时
     * 由数据包自行添加。
     */
    private void immunityAndSources() {
        tag(ModTags.Blocks.ANCHOR_PROTOTYPE_IMMUNE)
                .add(ModBlocks.OBSERVER_CORE.get())
                .add(ModBlocks.THRONE_BLOCK.get());
        tag(ModTags.Blocks.MUTATION_IMMUNE)
                .add(ModBlocks.OBSERVER_CORE.get())
                .add(ModBlocks.THRONE_BLOCK.get())
                .add(
                        Blocks.BEDROCK, Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID,
                        Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
                        Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.MOVING_PISTON,
                        Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL,
                        Blocks.REINFORCED_DEEPSLATE, Blocks.BUDDING_AMETHYST,
                        Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT,
                        Blocks.WATER, Blocks.LAVA);
        tag(ModTags.Blocks.MUTATION_SOURCE_EXTRA);
    }

    // ------------------------------------------------------------------
    // 大池
    // ------------------------------------------------------------------

    /**
     * 大池（"总池"）。
     * <p>
     * 作用只有一个：以 {@code wild_chance} 的概率把抽取<b>整枝</b>切到它身上，
     * 保证长尾随机性。它<b>不是</b>成员关系，所以不会把语义池连成一个连通分量
     * ——否则所有语义池会立刻塌缩成"全世界"，局部结构全没了。
     * <p>
     * 注意 {@code wild_auto_include}（默认开）还会把所有"完整方块且无方块实体"的方块
     * 自动算进大池，所以这里的清单主要是给"想收紧到只有清单内容"的配置用的，
     * 外加非完整方块（它们不在自动纳入的范围内）。
     */
    private void wildPools() {
        tag(ModTags.Blocks.MUTATION_POOL_WILD)
                .addTag(BlockTags.STONE_ORE_REPLACEABLES)
                .addTag(BlockTags.STAIRS)
                .addTag(BlockTags.SLABS)
                .addTag(BlockTags.FENCES)
                .addTag(BlockTags.FENCE_GATES)
                .addTag(BlockTags.WALLS)
                .addTag(BlockTags.TRAPDOORS)
                .addTag(BlockTags.WOOL_CARPETS)
                .add(PANES)
                .add(
                        // 石头衍生
                        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                        Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                        Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.BRICKS,
                        Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                        Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES,
                        Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF,
                        Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
                        Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
                        Blocks.CHISELED_SANDSTONE,
                        Blocks.SMOOTH_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE,
                        Blocks.SMOOTH_BASALT,
                        // 泥土/地表
                        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
                        Blocks.PODZOL, Blocks.MYCELIUM,
                        Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.MUD,
                        Blocks.PACKED_MUD, Blocks.MUD_BRICKS, Blocks.MOSS_BLOCK,
                        Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
                        // 雪/冰
                        Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
                        // 木材与木制品
                        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
                        Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.CHERRY_LOG, Blocks.MANGROVE_LOG,
                        Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_BIRCH_LOG,
                        Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
                        Blocks.STRIPPED_CHERRY_LOG, Blocks.STRIPPED_MANGROVE_LOG,
                        Blocks.OAK_WOOD, Blocks.SPRUCE_WOOD, Blocks.BIRCH_WOOD, Blocks.JUNGLE_WOOD,
                        Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD, Blocks.CHERRY_WOOD, Blocks.MANGROVE_WOOD,
                        Blocks.STRIPPED_OAK_WOOD, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STRIPPED_BIRCH_WOOD,
                        Blocks.STRIPPED_JUNGLE_WOOD, Blocks.STRIPPED_ACACIA_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD,
                        Blocks.STRIPPED_CHERRY_WOOD, Blocks.STRIPPED_MANGROVE_WOOD,
                        Blocks.BAMBOO_BLOCK, Blocks.STRIPPED_BAMBOO_BLOCK,
                        Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
                        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.CHERRY_PLANKS, Blocks.MANGROVE_PLANKS,
                        Blocks.BOOKSHELF, Blocks.HAY_BLOCK, Blocks.MELON, Blocks.PUMPKIN,
                        Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
                        // 矿物与水晶
                        Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE,
                        Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.EMERALD_ORE, Blocks.DIAMOND_ORE,
                        Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE,
                        Blocks.DEEPSLATE_GOLD_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
                        Blocks.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
                        Blocks.COAL_BLOCK, Blocks.IRON_BLOCK, Blocks.COPPER_BLOCK, Blocks.GOLD_BLOCK,
                        Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.EMERALD_BLOCK, Blocks.DIAMOND_BLOCK,
                        Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_GOLD_BLOCK,
                        Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
                        Blocks.CHISELED_QUARTZ_BLOCK,
                        Blocks.AMETHYST_BLOCK,
                        // 陶瓦/混凝土/羊毛（建筑色块）
                        Blocks.TERRACOTTA,
                        Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                        Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
                        Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                        Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
                        Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
                        Blocks.BLACK_TERRACOTTA,
                        Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.MAGENTA_GLAZED_TERRACOTTA,
                        Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA,
                        Blocks.PINK_GLAZED_TERRACOTTA, Blocks.GRAY_GLAZED_TERRACOTTA, Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA,
                        Blocks.CYAN_GLAZED_TERRACOTTA, Blocks.PURPLE_GLAZED_TERRACOTTA, Blocks.BLUE_GLAZED_TERRACOTTA,
                        Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA, Blocks.RED_GLAZED_TERRACOTTA,
                        Blocks.BLACK_GLAZED_TERRACOTTA,
                        Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE,
                        Blocks.LIGHT_BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE,
                        Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
                        Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE,
                        Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE,
                        Blocks.BLACK_CONCRETE,
                        Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL,
                        Blocks.LIGHT_BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
                        Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL,
                        Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL,
                        Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL,
                        Blocks.BLACK_WOOL,
                        // 海洋
                        Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN,
                        Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK,
                        Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK,
                        Blocks.DEAD_TUBE_CORAL_BLOCK, Blocks.DEAD_BRAIN_CORAL_BLOCK, Blocks.DEAD_BUBBLE_CORAL_BLOCK,
                        Blocks.DEAD_FIRE_CORAL_BLOCK, Blocks.DEAD_HORN_CORAL_BLOCK,
                        Blocks.SPONGE, Blocks.WET_SPONGE,
                        // 其他
                        Blocks.BONE_BLOCK, Blocks.SCULK,
                        Blocks.HONEY_BLOCK, Blocks.HONEYCOMB_BLOCK, Blocks.SLIME_BLOCK,
                        Blocks.DRIED_KELP_BLOCK
                );
        // 下界专属大池：黑石/玄武岩/下界砖/灵魂沙/岩浆/下界木/矿物，画风统一
        tag(ModTags.Blocks.MUTATION_POOL_WILD_NETHER)
                .add(
                        Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM,
                        Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE,
                        Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
                        Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE,
                        Blocks.BASALT, Blocks.POLISHED_BASALT, Blocks.SMOOTH_BASALT,
                        Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS, Blocks.CHISELED_NETHER_BRICKS,
                        Blocks.RED_NETHER_BRICKS,
                        Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK, Blocks.GLOWSTONE,
                        Blocks.SHROOMLIGHT,
                        Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
                        Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM,
                        Blocks.CRIMSON_HYPHAE, Blocks.WARPED_HYPHAE,
                        Blocks.STRIPPED_CRIMSON_HYPHAE, Blocks.STRIPPED_WARPED_HYPHAE,
                        Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
                        Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK,
                        Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS,
                        Blocks.NETHERITE_BLOCK,
                        Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS,
                        Blocks.QUARTZ_PILLAR, Blocks.CHISELED_QUARTZ_BLOCK,
                        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
                        Blocks.BONE_BLOCK
                );
        // 末地专属大池：末地石/紫珀/黑曜石，画风统一
        tag(ModTags.Blocks.MUTATION_POOL_WILD_END)
                .add(
                        Blocks.END_STONE, Blocks.END_STONE_BRICKS,
                        Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR,
                        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN
                );
    }

    // ------------------------------------------------------------------
    // 语义池
    // ------------------------------------------------------------------

    /**
     * 语义池：突变真正的主要去处。
     * <p>
     * 一个方块抽中突变时，候选集是"它所属<b>全部</b>池的并集 ∩ 它自己的形态类"。
     * 所以池怎么切就等于世界的局部结构怎么定：石头只在石头里打转、木头只在木头里打转，
     * 交叉归类（例如把 {@code smooth_basalt} 同时放进 stone 与 nether）会让两边互相渗透。
     */
    private void semanticPools() {
        tag(ModTags.Blocks.mutationPool("stone"))
                .addTag(BlockTags.STONE_ORE_REPLACEABLES)
                .add(
                        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                        Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                        Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.BRICKS,
                        Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                        Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES,
                        Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF,
                        Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
                        Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
                        Blocks.CHISELED_SANDSTONE,
                        Blocks.SMOOTH_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE,
                        Blocks.SMOOTH_BASALT, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK);
        tag(ModTags.Blocks.mutationPool("dirt"))
                .add(
                        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
                        Blocks.PODZOL, Blocks.MYCELIUM,
                        Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.MUD,
                        Blocks.PACKED_MUD, Blocks.MUD_BRICKS, Blocks.MOSS_BLOCK);
        tag(ModTags.Blocks.mutationPool("wood"))
                .addTag(BlockTags.LOGS_THAT_BURN)
                .addTag(BlockTags.PLANKS)
                .add(
                        Blocks.OAK_WOOD, Blocks.SPRUCE_WOOD, Blocks.BIRCH_WOOD, Blocks.JUNGLE_WOOD,
                        Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD, Blocks.CHERRY_WOOD, Blocks.MANGROVE_WOOD,
                        Blocks.STRIPPED_OAK_WOOD, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STRIPPED_BIRCH_WOOD,
                        Blocks.STRIPPED_JUNGLE_WOOD, Blocks.STRIPPED_ACACIA_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD,
                        Blocks.STRIPPED_CHERRY_WOOD, Blocks.STRIPPED_MANGROVE_WOOD,
                        Blocks.BAMBOO_BLOCK, Blocks.STRIPPED_BAMBOO_BLOCK);
        tag(ModTags.Blocks.mutationPool("wool")).addTag(BlockTags.WOOL);
        tag(ModTags.Blocks.mutationPool("concrete"))
                .add(
                        Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE,
                        Blocks.LIGHT_BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE,
                        Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
                        Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE,
                        Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE,
                        Blocks.BLACK_CONCRETE);
        tag(ModTags.Blocks.mutationPool("terracotta"))
                .add(Blocks.TERRACOTTA)
                .add(
                        Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                        Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
                        Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                        Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
                        Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
                        Blocks.BLACK_TERRACOTTA)
                .add(
                        Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.MAGENTA_GLAZED_TERRACOTTA,
                        Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA,
                        Blocks.PINK_GLAZED_TERRACOTTA, Blocks.GRAY_GLAZED_TERRACOTTA, Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA,
                        Blocks.CYAN_GLAZED_TERRACOTTA, Blocks.PURPLE_GLAZED_TERRACOTTA, Blocks.BLUE_GLAZED_TERRACOTTA,
                        Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA, Blocks.RED_GLAZED_TERRACOTTA,
                        Blocks.BLACK_GLAZED_TERRACOTTA);
        tag(ModTags.Blocks.mutationPool("ore"))
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
                        Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.AMETHYST_BLOCK);
        tag(ModTags.Blocks.mutationPool("quartz"))
                .add(Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS,
                        Blocks.QUARTZ_PILLAR, Blocks.CHISELED_QUARTZ_BLOCK);
        tag(ModTags.Blocks.mutationPool("ice"))
                .add(Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE);
        tag(ModTags.Blocks.mutationPool("ocean"))
                .add(
                        Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN,
                        Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK,
                        Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK,
                        Blocks.DEAD_TUBE_CORAL_BLOCK, Blocks.DEAD_BRAIN_CORAL_BLOCK, Blocks.DEAD_BUBBLE_CORAL_BLOCK,
                        Blocks.DEAD_FIRE_CORAL_BLOCK, Blocks.DEAD_HORN_CORAL_BLOCK,
                        Blocks.SPONGE, Blocks.WET_SPONGE);
        tag(ModTags.Blocks.mutationPool("nether"))
                .add(
                        Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM,
                        Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE,
                        Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
                        Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE,
                        Blocks.BASALT, Blocks.POLISHED_BASALT, Blocks.SMOOTH_BASALT,
                        Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS, Blocks.CHISELED_NETHER_BRICKS,
                        Blocks.RED_NETHER_BRICKS,
                        Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK, Blocks.GLOWSTONE,
                        Blocks.SHROOMLIGHT,
                        Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
                        Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM,
                        Blocks.CRIMSON_HYPHAE, Blocks.WARPED_HYPHAE,
                        Blocks.STRIPPED_CRIMSON_HYPHAE, Blocks.STRIPPED_WARPED_HYPHAE,
                        Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
                        Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK,
                        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        tag(ModTags.Blocks.mutationPool("end"))
                .add(Blocks.END_STONE, Blocks.END_STONE_BRICKS, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR,
                        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        tag(ModTags.Blocks.mutationPool("misc"))
                .add(
                        Blocks.BONE_BLOCK, Blocks.SCULK,
                        Blocks.HONEY_BLOCK, Blocks.HONEYCOMB_BLOCK, Blocks.SLIME_BLOCK,
                        Blocks.DRIED_KELP_BLOCK,
                        Blocks.BOOKSHELF, Blocks.HAY_BLOCK, Blocks.MELON, Blocks.PUMPKIN,
                        Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK);
    }

    /**
     * 形态族的池：楼梯只跟楼梯、半砖只跟半砖。
     * <p>
     * 严格说这些池的形状门控已经由形态类保证了（即使不加池，它们也只会在大池的同形态切片里互变），
     * 但显式写成池有两个好处：一是"各自范围之内互相突变"在标签层面就能一眼看出来，
     * 二是数据包想调整某个形态族的候选范围时改这里就行，不必去动大池。
     */
    private void shapePools() {
        tag(ModTags.Blocks.mutationPool("stairs")).addTag(BlockTags.STAIRS);
        tag(ModTags.Blocks.mutationPool("slabs")).addTag(BlockTags.SLABS);
        tag(ModTags.Blocks.mutationPool("fences")).addTag(BlockTags.FENCES);
        tag(ModTags.Blocks.mutationPool("fence_gates")).addTag(BlockTags.FENCE_GATES);
        tag(ModTags.Blocks.mutationPool("walls")).addTag(BlockTags.WALLS);
        tag(ModTags.Blocks.mutationPool("trapdoors")).addTag(BlockTags.TRAPDOORS);
        tag(ModTags.Blocks.mutationPool("carpets")).addTag(BlockTags.WOOL_CARPETS);
        tag(ModTags.Blocks.mutationPool("panes")).add(PANES);
    }

    /**
     * 颜色池：跨材质的"多重池"示范。
     * <p>
     * 白色羊毛同时属于 {@code wool} 与 {@code color/white}，抽目标时两个池会<b>并起来</b>
     * 一起抽，所以白色羊毛既能变成别的羊毛，也能变成白色混凝土/陶瓦。
     * 这就是"一个方块纳入多种突变池、随机时一并抽取"的落地方式。
     */
    private void colorPools() {
        colorPool("white", Blocks.WHITE_WOOL, Blocks.WHITE_CONCRETE, Blocks.WHITE_TERRACOTTA,
                Blocks.WHITE_GLAZED_TERRACOTTA);
        colorPool("orange", Blocks.ORANGE_WOOL, Blocks.ORANGE_CONCRETE, Blocks.ORANGE_TERRACOTTA,
                Blocks.ORANGE_GLAZED_TERRACOTTA);
        colorPool("magenta", Blocks.MAGENTA_WOOL, Blocks.MAGENTA_CONCRETE, Blocks.MAGENTA_TERRACOTTA,
                Blocks.MAGENTA_GLAZED_TERRACOTTA);
        colorPool("light_blue", Blocks.LIGHT_BLUE_WOOL, Blocks.LIGHT_BLUE_CONCRETE,
                Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA);
        colorPool("yellow", Blocks.YELLOW_WOOL, Blocks.YELLOW_CONCRETE, Blocks.YELLOW_TERRACOTTA,
                Blocks.YELLOW_GLAZED_TERRACOTTA);
        colorPool("lime", Blocks.LIME_WOOL, Blocks.LIME_CONCRETE, Blocks.LIME_TERRACOTTA,
                Blocks.LIME_GLAZED_TERRACOTTA);
        colorPool("pink", Blocks.PINK_WOOL, Blocks.PINK_CONCRETE, Blocks.PINK_TERRACOTTA,
                Blocks.PINK_GLAZED_TERRACOTTA);
        colorPool("gray", Blocks.GRAY_WOOL, Blocks.GRAY_CONCRETE, Blocks.GRAY_TERRACOTTA,
                Blocks.GRAY_GLAZED_TERRACOTTA);
        colorPool("light_gray", Blocks.LIGHT_GRAY_WOOL, Blocks.LIGHT_GRAY_CONCRETE,
                Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA);
        colorPool("cyan", Blocks.CYAN_WOOL, Blocks.CYAN_CONCRETE, Blocks.CYAN_TERRACOTTA,
                Blocks.CYAN_GLAZED_TERRACOTTA);
        colorPool("purple", Blocks.PURPLE_WOOL, Blocks.PURPLE_CONCRETE, Blocks.PURPLE_TERRACOTTA,
                Blocks.PURPLE_GLAZED_TERRACOTTA);
        colorPool("blue", Blocks.BLUE_WOOL, Blocks.BLUE_CONCRETE, Blocks.BLUE_TERRACOTTA,
                Blocks.BLUE_GLAZED_TERRACOTTA);
        colorPool("brown", Blocks.BROWN_WOOL, Blocks.BROWN_CONCRETE, Blocks.BROWN_TERRACOTTA,
                Blocks.BROWN_GLAZED_TERRACOTTA);
        colorPool("green", Blocks.GREEN_WOOL, Blocks.GREEN_CONCRETE, Blocks.GREEN_TERRACOTTA,
                Blocks.GREEN_GLAZED_TERRACOTTA);
        colorPool("red", Blocks.RED_WOOL, Blocks.RED_CONCRETE, Blocks.RED_TERRACOTTA,
                Blocks.RED_GLAZED_TERRACOTTA);
        colorPool("black", Blocks.BLACK_WOOL, Blocks.BLACK_CONCRETE, Blocks.BLACK_TERRACOTTA,
                Blocks.BLACK_GLAZED_TERRACOTTA);
    }

    private void colorPool(String color, Block... blocks) {
        var builder = tag(ModTags.Blocks.mutationPool("color/" + color));
        builder.add(blocks);
    }

    /**
     * 引导模型概念标签（方案 A，2026-08-21）：训练目标按覆盖率指认概念，概念邻域 = 标签成员。
     * <p>
     * 与突变池是两套东西：概念由<b>训练数据</b>动态指认（引导模型），池是静态配置（失焦本身）。
     */
    private void concepts() {
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
                        Blocks.AMETHYST_BLOCK);
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
                        Blocks.END_STONE, Blocks.END_STONE_BRICKS);
        tag(ModTags.Blocks.CONCEPT_GLASS)
                .add(Blocks.GLASS, Blocks.TINTED_GLASS)
                .add(
                        Blocks.WHITE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
                        Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS,
                        Blocks.PINK_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS,
                        Blocks.CYAN_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS,
                        Blocks.BROWN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.RED_STAINED_GLASS,
                        Blocks.BLACK_STAINED_GLASS)
                .add(Blocks.GLASS_PANE)
                .add(
                        Blocks.WHITE_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE, Blocks.MAGENTA_STAINED_GLASS_PANE,
                        Blocks.LIGHT_BLUE_STAINED_GLASS_PANE, Blocks.YELLOW_STAINED_GLASS_PANE, Blocks.LIME_STAINED_GLASS_PANE,
                        Blocks.PINK_STAINED_GLASS_PANE, Blocks.GRAY_STAINED_GLASS_PANE, Blocks.LIGHT_GRAY_STAINED_GLASS_PANE,
                        Blocks.CYAN_STAINED_GLASS_PANE, Blocks.PURPLE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS_PANE,
                        Blocks.BROWN_STAINED_GLASS_PANE, Blocks.GREEN_STAINED_GLASS_PANE, Blocks.RED_STAINED_GLASS_PANE,
                        Blocks.BLACK_STAINED_GLASS_PANE);
        tag(ModTags.Blocks.CONCEPT_TERRACOTTA)
                .add(Blocks.TERRACOTTA)
                .add(
                        Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                        Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
                        Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                        Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
                        Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
                        Blocks.BLACK_TERRACOTTA)
                .add(
                        Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.MAGENTA_GLAZED_TERRACOTTA,
                        Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA,
                        Blocks.PINK_GLAZED_TERRACOTTA, Blocks.GRAY_GLAZED_TERRACOTTA, Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA,
                        Blocks.CYAN_GLAZED_TERRACOTTA, Blocks.PURPLE_GLAZED_TERRACOTTA, Blocks.BLUE_GLAZED_TERRACOTTA,
                        Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA, Blocks.RED_GLAZED_TERRACOTTA,
                        Blocks.BLACK_GLAZED_TERRACOTTA);
        tag(ModTags.Blocks.CONCEPT_WOOL)
                .addTag(BlockTags.WOOL)
                .addTag(BlockTags.WOOL_CARPETS);
    }
}
