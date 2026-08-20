package com.zhizhiwang.focal_decay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 王座四根角柱的"折跃门式"信标光束（客户端渲染）。
 * 复用末地折跃门的 end_gateway_beam 贴图与 BeaconRenderer 绘制；
 * 单人可经集成服务器取世界种子（与失焦预览相同的多人限制）。
 */
public final class ThroneBeamRenderer {
    private static final ResourceLocation BEAM_LOCATION =
            ResourceLocation.withDefaultNamespace("textures/entity/end_gateway_beam.png");
    private static final int BEAM_HEIGHT = 256;
    private static final int MAX_DISTANCE = 512;
    /** 四根角柱相对基座中心的 x/z 偏移。 */
    private static final int[][] PILLARS = {{-6, -6}, {6, -6}, {-6, 6}, {6, 6}};

    private ThroneBeamRenderer() {
    }

    public static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != Level.END) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || server.overworld() == null) {
            return; // 多人模式暂无世界种子同步
        }
        BlockPos throne = ThroneStructure.thronePos(server.overworld().getSeed());
        Vec3 camera = event.getCamera().getPosition();
        if (throne.distToCenterSqr(camera) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        // 结构实际生成后才显示光束
        if (!level.getBlockState(throne).is(ModBlocks.THRONE_BLOCK.get())) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffer = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        long gameTime = level.getGameTime();
        int color = DyeColor.PURPLE.getTextureDiffuseColor();
        int beamY = throne.getY() + 17; // 角柱顶（含顶部的末地棒）

        for (int[] pillar : PILLARS) {
            poseStack.pushPose();
            poseStack.translate(
                    throne.getX() + pillar[0] - camera.x,
                    beamY - camera.y,
                    throne.getZ() + pillar[1] - camera.z);
            BeaconRenderer.renderBeaconBeam(
                    poseStack, buffer, BEAM_LOCATION, partialTick, 1.0F,
                    gameTime, 0, BEAM_HEIGHT, color, 0.15F, 0.175F);
            poseStack.popPose();
        }
    }
}
