package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * 给模板里<b>所有容器</b>（箱子、木桶……）填上战利品表的处理器。
 * <p>
 * <b>坑：1.21.1 里"容器战利品"的正确写法是 NBT 里的 {@code LootTable} 字符串。</b>
 * 网上常见的说法是"用 {@code components.minecraft:container_loot} 组件"，但那条路走不通：
 * {@code BlockEntity.loadWithComponents} 只把 {@code components} 解析进 {@code this.components} 字段，
 * <b>并不会</b>回调 {@code applyImplicitComponents}，所以 {@code RandomizableContainerBlockEntity.lootTable}
 * 一直是 null，箱子开了是空的。真正读 NBT 的是各容器自己：
 * {@code ChestBlockEntity.loadAdditional} 里第一句就是
 * {@code if (!this.tryLoadLootTable(tag)) ContainerHelper.loadAllItems(...)}。
 * <p>
 * <b>种子不用管</b>：{@code StructureTemplate.placeInWorld} 在放置带 NBT 的方块实体时，
 * 只要它实现了 {@link RandomizableContainer}，就会自动往 NBT 里塞一个随机的 {@code LootTableSeed}。
 * <p>
 * 使用方式（数据侧）：
 * <pre>{@code { "processor_type": "focal_decay:container_loot" } }</pre>
 * 战利品表取配置项 {@code server.site_loot_table}。要在这个结构里换别的表，写
 * {@code "loot_table": "命名空间:路径"} 覆盖即可（例如结构方块临时搭的测试结构）。
 */
public class ContainerLootProcessor extends StructureProcessor {

    public static final MapCodec<ContainerLootProcessor> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            ResourceKey.codec(Registries.LOOT_TABLE)
                    .optionalFieldOf("loot_table")
                    .forGetter(p -> p.lootTable)
    ).apply(inst, ContainerLootProcessor::new));

    /** 显式指定的战利品表；空 = 用配置项 {@code site_loot_table}。 */
    private final Optional<ResourceKey<LootTable>> lootTable;

    public ContainerLootProcessor() {
        this(Optional.empty());
    }

    public ContainerLootProcessor(Optional<ResourceKey<LootTable>> lootTable) {
        this.lootTable = lootTable;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 沿用 {@code relativeBlockInfo.pos()}（已换算好的世界坐标）—— 见 {@link AnchorModelProcessor} 的同类说明。
     */
    @Override
    public StructureTemplate.StructureBlockInfo process(LevelReader level, BlockPos offset, BlockPos pos,
                                                        StructureTemplate.StructureBlockInfo blockInfo,
                                                        StructureTemplate.StructureBlockInfo relativeBlockInfo,
                                                        StructurePlaceSettings settings,
                                                        StructureTemplate template) {
        if (!isRandomizableContainer(relativeBlockInfo.state())) {
            return relativeBlockInfo;
        }
        ResourceKey<LootTable> table = this.lootTable.orElseGet(ContainerLootProcessor::configuredTable);
        if (table == null) {
            return relativeBlockInfo;
        }
        CompoundTag tag = relativeBlockInfo.nbt() == null
                ? new CompoundTag()
                : relativeBlockInfo.nbt().copy();
        // 模板里可能已经带了 LootTable（结构方块保存时就写了），这里一律以处理器为准：
        // "改了配置却没生效"比"模板被覆盖"难查得多。
        tag.putString(RandomizableContainer.LOOT_TABLE_TAG, table.location().toString());
        return new StructureTemplate.StructureBlockInfo(
                relativeBlockInfo.pos(), relativeBlockInfo.state(), tag);
    }

    /** 配置项里的战利品表 ID；非法值只记一条警告并放弃处理，绝不让世界生成崩掉。 */
    @Nullable
    private static ResourceKey<LootTable> configuredTable() {
        String raw = FocalDecayConfig.SITE_LOOT_TABLE.get();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.trim());
        if (id == null) {
            FocalDecay.LOGGER.warn("[focal_decay] container_loot processor: site_loot_table is not a valid ResourceLocation: {}",
                    raw);
            return null;
        }
        return ResourceKey.create(Registries.LOOT_TABLE, id);
    }

    /**
     * 方块放下之后是否会变成"可随机填充的容器"。
     * <p>
     * 只能靠 {@code EntityBlock.newBlockEntity} 问一句：方块状态本身不携带方块实体类型，
     * 没有别的映射表可查（{@code RandomizableContainer} 是方块实体实现的接口）。
     * 工厂方法在各类容器里都是纯构造，不会碰到世界。
     */
    private static boolean isRandomizableContainer(BlockState state) {
        if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock entityBlock)) {
            return false;
        }
        return entityBlock.newBlockEntity(BlockPos.ZERO, state) instanceof RandomizableContainer;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructures.CONTAINER_LOOT_PROCESSOR.get();
    }
}
