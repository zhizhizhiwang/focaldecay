package com.zhizhiwang.focal_decay.structure;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * 给结构里的观测者基座塞入随机稳定模型的处理器。
 * <p>
 * <b>为什么用处理器而不是"直接改方块实体"：</b>{@code StructureProcessor.process} 是在方块
 * <b>真正放置之前</b>被调用的（它负责决定"要放什么"）。此时方块实体还不存在，所以不能去改它；
 * 正确做法是<b>返回一个带 NBT 的 {@link StructureTemplate.StructureBlockInfo}</b>，
 * 让原版在放置方块时据此创建出带数据的方块实体 —— 原版结构里"宝箱自带战利品"就是这个机制。
 * <p>
 * 抽取的物品形态与锚的存档格式一致（{@code Model} 标签里的物品栈），因此基座放下去就已经装了模型，
 * 玩家开箱即可取出。模型带完整 DataComponent（训练数据等），与合成/训练得到的完全等价。
 * <p>
 * 使用方式（数据侧）：结构 JSON 的 {@code processors} 里写一行
 * {@code {"processor_type": "focal_decay:anchor_model"}}，或额外给 {@code "loot_table": "命名空间:路径"}
 * 换一张自己的表。世界生成与 {@code /place structure|template} 都会生效（用的都是服务端确定性随机，
 * 同一坐标结果固定，便于反复测试）。
 */
public class AnchorModelProcessor extends StructureProcessor {

    /** 默认战利品表：三档随机。 */
    public static final ResourceKey<LootTable> DEFAULT_LOOT =
            ResourceKey.create(Registries.LOOT_TABLE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "structure/anchor_model"));

    public static final MapCodec<AnchorModelProcessor> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            ResourceKey.codec(Registries.LOOT_TABLE)
                    .optionalFieldOf("loot_table", DEFAULT_LOOT)
                    .forGetter(p -> p.lootTable)
    ).apply(inst, AnchorModelProcessor::new));

    private final ResourceKey<LootTable> lootTable;

    public AnchorModelProcessor() {
        this(DEFAULT_LOOT);
    }

    public AnchorModelProcessor(ResourceKey<LootTable> lootTable) {
        this.lootTable = lootTable;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 注意参数含义：{@code relativeBlockInfo} 的 {@code pos()} 是<b>已经换算好的世界坐标</b>
     * （{@code StructureTemplate.processBlockInfos} 里 = 相对坐标 + offset），而 {@code blockInfo}
     * 才是模板原始坐标。返回值就是最终要放置的东西，所以必须沿用 {@code relativeBlockInfo.pos()}。
     */
    @Override
    public StructureTemplate.StructureBlockInfo process(LevelReader level, BlockPos offset, BlockPos pos,
                                                        StructureTemplate.StructureBlockInfo blockInfo,
                                                        StructureTemplate.StructureBlockInfo relativeBlockInfo,
                                                        StructurePlaceSettings settings,
                                                        StructureTemplate template) {
        if (!relativeBlockInfo.state().is(ModBlocks.ANCHOR_PROTOTYPE.get())) {
            return relativeBlockInfo;
        }
        // ServerLevelAccessor 同时覆盖世界生成（WorldGenLevel）与 /place 指令（ServerLevel），
        // 两条路径都要生效，否则没法用 /place 验证。
        if (!(level instanceof ServerLevelAccessor accessor)) {
            return relativeBlockInfo;
        }
        // 已经有模型（例如模板里手工放了）就不要覆盖
        CompoundTag existing = relativeBlockInfo.nbt();
        if (existing != null && existing.contains(AnchorPrototypeBlockEntity.TAG_MODEL)) {
            return relativeBlockInfo;
        }
        ServerLevel serverLevel = accessor.getLevel();

        // 用确定性随机源（世界种子 + 位置），而不是取 level 的随机数：
        // 世界生成在多线程下工作，独立随机源既避免共享状态，又让同一份存档的结果可复现。
        RandomSource random = RandomSource.create(
                serverLevel.getSeed() * 0x9E3779B97F4A7C15L
                        ^ (long) pos.getX() * 0xC2B2AE3D27D4EB4FL
                        ^ (long) pos.getY() * 0x165667B19E3779F9L
                        ^ (long) pos.getZ() * 0x27D4EB2F165667C5L);

        ItemStack model = rollModel(serverLevel, relativeBlockInfo.pos(), random);
        if (model.isEmpty()) {
            return relativeBlockInfo;
        }

        CompoundTag tag = existing == null ? new CompoundTag() : existing.copy();
        tag.put(AnchorPrototypeBlockEntity.TAG_MODEL, saveStack(serverLevel, model));

        return new StructureTemplate.StructureBlockInfo(
                relativeBlockInfo.pos(), relativeBlockInfo.state(), tag);
    }

    /** 从战利品表抽一件模型。 */
    private ItemStack rollModel(ServerLevel serverLevel, BlockPos pos, RandomSource random) {
        LootTable table = serverLevel.getServer().reloadableRegistries().getLootTable(this.lootTable);
        if (table == LootTable.EMPTY) {
            FocalDecay.LOGGER.warn("[focal_decay] anchor_model processor: loot table {} is missing", this.lootTable.location());
            return ItemStack.EMPTY;
        }
        LootParams params = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .create(LootContextParamSets.COMMAND);
        var items = table.getRandomItems(params, random);
        return items.isEmpty() ? ItemStack.EMPTY : items.get(0);
    }

    /**
     * 序列化成方块实体期望的 NBT。
     * <p>
     * 用 {@code saveOptional} 与 {@code AnchorPrototypeBlockEntity} 的读写保持完全一致。
     * 注意 NeoForge 把返回值放宽成了 {@link Tag}（实际运行时是 CompoundTag），
     * 所以这里跟着返回 Tag，交给 {@code CompoundTag#put} 自己收。
     */
    private static Tag saveStack(ServerLevel level, ItemStack stack) {
        return stack.saveOptional(level.registryAccess());
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructures.ANCHOR_MODEL_PROCESSOR.get();
    }
}
