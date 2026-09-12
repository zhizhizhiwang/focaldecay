package com.zhizhiwang.focal_decay.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;

/**
 * {@link SingleTemplateStructure} 的部件：复用原版 {@link TemplateStructurePiece}
 * （它负责把模板按包围盒裁剪着摆下去，跨区块由原版逐区块回调），只额外挂上处理器。
 */
public class SingleTemplatePiece extends TemplateStructurePiece {

    public SingleTemplatePiece(StructureTemplateManager templateManager, ResourceLocation template,
                               Rotation rotation, List<StructureProcessor> processors, BlockPos pos) {
        super(ModStructures.SINGLE_TEMPLATE_PIECE.get(), 0, templateManager, template,
                template.toString(), settings(rotation, processors), pos);
    }

    /**
     * 从存档反序列化的构造器。
     * <p>
     * 注意：原版的 {@code TemplateStructurePiece} 只把模板路径与位置写进 NBT，
     * <b>不保存 placeSettings</b>，所以这里没法还原 JSON 里的处理器 —— 用默认的顶上。
     * 这不影响结果：模型是在方块真正放置的那一刻（{@code postProcess}）就写进方块实体 NBT 的，
     * 存档里记着的部件只是给结构定位/查询用，不会再摆一次。
     */
    public SingleTemplatePiece(StructureTemplateManager templateManager, CompoundTag tag) {
        super(ModStructures.SINGLE_TEMPLATE_PIECE.get(), tag, templateManager,
                location -> settings(Rotation.NONE, SingleTemplateStructure.DEFAULT_PROCESSORS));
    }

    private static StructurePlaceSettings settings(Rotation rotation, List<StructureProcessor> processors) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        processors.forEach(settings::addProcessor);
        return settings;
    }

    /**
     * 模板里 {@code structure_block}（data 模式）标记的回调。
     * <p>
     * 目前没有需要数据标记的地方，留空实现；标记方块自己不会出现在成品里。
     */
    @Override
    protected void handleDataMarker(String name, BlockPos pos, ServerLevelAccessor level,
                                    RandomSource random, BoundingBox box) {
    }
}
