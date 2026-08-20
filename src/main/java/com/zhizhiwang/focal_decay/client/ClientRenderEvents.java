package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 客户端渲染事件挂载（设计大纲 §7）：
 *  - RenderLevelStageEvent：捕获视锥 + 世界渲染末尾推进 observer_veil 后处理；
 *  - ClientTickEvent.Post：周期检测与表面扫描。
 */
@EventBusSubscriber(modid = FocalDecay.MODID, value = Dist.CLIENT)
public final class ClientRenderEvents {

    private ClientRenderEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            ClientRenderCache.INSTANCE.captureFrustum(event.getFrustum());
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            ThroneBeamRenderer.render(event);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientRenderCache.INSTANCE.tick();
    }
}
