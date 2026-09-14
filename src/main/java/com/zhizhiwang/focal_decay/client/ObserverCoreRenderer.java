package com.zhizhiwang.focal_decay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ObserverCoreBlock;
import com.zhizhiwang.focal_decay.block.entity.ObserverCoreBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 观测者核心的转子渲染器。
 * <p>
 * 底座留在区块网格里（observer_core 模型）继续吃原版的面明暗与 AO，这里只画会动的那颗转子。
 * 转子用 {@link ModelBlockRenderer#tesselateBlock} 渲染——走的是和区块网格同一套着色代码
 * （{@code PistonHeadRenderer} 画移动中的活塞也是这么干的），所以两部分观感一致。
 * <p>
 * 两个必须注意的点：
 * <ul>
 *   <li><b>透明度</b>：转子贴图里有 alpha=0 的像素，必须用 {@code RenderType.cutout()}，
 *       否则透明处会被渲染成黑块。</li>
 *   <li><b>发光</b>：核心 powered 时 lightLevel=15，而 tesselateBlock 走真实 level 取光，
 *       原版 {@code LevelRenderer.getLightColor} 会把方块自身的 lightEmission 并进去，
 *       所以转子激活后自动满亮、未激活时照常受世界光照——不需要任何 hack。</li>
 * </ul>
 */
public class ObserverCoreRenderer implements BlockEntityRenderer<ObserverCoreBlockEntity> {

    private static final ResourceLocation ROTOR_IDLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "block/observer_core_rotor");
    private static final ResourceLocation ROTOR_ACTIVE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "block/observer_core_rotor_active");

    /** 需要在 ModelEvent.RegisterAdditional 里注册，BER 才能从模型管理器取到这两个模型。 */
    public static final ModelResourceLocation ROTOR_IDLE_MODEL = ModelResourceLocation.standalone(ROTOR_IDLE_TEXTURE);
    public static final ModelResourceLocation ROTOR_ACTIVE_MODEL = ModelResourceLocation.standalone(ROTOR_ACTIVE_TEXTURE);

    /**
     * 转子几何顶端相对方块底部的高度（格）：模型 y 2..8 px，顶面 8px = 8/16。
     * <p>
     * 也可以写成"中心 + 半高"，而半高正好等于截面半宽——转子是 6×6×6 px 的立方体，
     * 所以刻意复用 {@link ObserverCoreBlockEntity#ROTOR_SWEEP_RADIUS}，别再写一份 3/16。
     */
    private static final double ROTOR_TOP = ObserverCoreBlockEntity.ROTOR_CENTER_Y
            + ObserverCoreBlockEntity.ROTOR_SWEEP_RADIUS;

    /**
     * 转子在竖直方向能到达的最高点（相对方块底部，单位：格）。
     * <p>
     * 由模型顶端 + 升起高度 + 浮动峰值推导，所以调 {@code observer_core_bob_amplitude}
     * 或改 {@link ObserverCoreBlockEntity#LIFT_HEIGHT} 后自动跟着变。
     * 默认参数下约 0.81 格——比整格矮，但旧代码写死过 1.0，这里改成实算值。
     * <p>
     * 绕竖轴旋转不改变高度，所以取"完全升起 + 浮动峰值"就是上界，不必再考虑旋转。
     * 浮动幅度每次调用都从配置读：它是运行时可改的，不能快照成常量。
     */
    private static double maxRotorTop() {
        return ROTOR_TOP
                + ObserverCoreBlockEntity.LIFT_HEIGHT * ObserverCoreBlockEntity.liftFractionFrom(Long.MAX_VALUE)
                + FocalDecayConfig.OBSERVER_CORE_BOB_AMPLITUDE.get();
    }

    private final ModelBlockRenderer modelRenderer;

    public ObserverCoreRenderer(BlockEntityRendererProvider.Context context) {
        this.modelRenderer = context.getBlockRenderDispatcher().getModelRenderer();
    }

    @Override
    public void render(ObserverCoreBlockEntity core, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        Level level = core.getLevel();
        if (level == null) {
            return;
        }
        BlockState state = core.getBlockState();
        BlockPos pos = core.getBlockPos();
        BakedModel model = Minecraft.getInstance().getModelManager()
                .getModel(state.getValue(ObserverCoreBlock.POWERED) ? ROTOR_ACTIVE_MODEL : ROTOR_IDLE_MODEL);

        poseStack.pushPose();
        // 竖直位移：升起 + 上下浮动
        poseStack.translate(0.0F,
                ObserverCoreBlockEntity.LIFT_HEIGHT * core.liftAmount(partialTick)
                        + core.bobOffset(level, state.getValue(ObserverCoreBlock.POWERED), partialTick),
                0.0F);
        // 绕方块中心的竖轴旋转：负角度 = 从上往下看顺时针
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-core.spinAngle(partialTick)));
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        VertexConsumer consumer = buffer.getBuffer(RenderType.cutout());
        // checkSides=false：转子是方块内部的子模型，不能按世界邻接关系剔面
        this.modelRenderer.tesselateBlock(level, model, state, pos, poseStack, consumer, false,
                RandomSource.create(), state.getSeed(pos), packedOverlay);
        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(ObserverCoreBlockEntity core) {
        // 转子会升起 + 浮动，最高点略高于整格（约 0.82 格）——旧版担心它被整个剔掉，
        // 上界按"模型顶端 + 升起 + 浮动峰值"实算，不再写死 1.0。
        // 水平方向沿用整格：转子截面只占 5..11 px，旋转外扩半径见 ROTOR_SWEEP_RADIUS，塞得下。
        BlockPos pos = core.getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + maxRotorTop(), pos.getZ() + 1.0D);
    }
}