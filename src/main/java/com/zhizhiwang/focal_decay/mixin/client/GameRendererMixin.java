package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 observer_veil 后处理移到第一人称手部渲染之后执行。
 * 原版顺序：LevelRenderer.renderLevel（含 AFTER_LEVEL 事件）→ 手部 → 后处理 → bindWrite。
 * 此前我们在 AFTER_LEVEL 处理整条 PostChain，导致后续手部渲染的目标/状态异常、手部不显示。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void focaldecay$processObserverVeil(DeltaTracker deltaTracker, CallbackInfo ci) {
        // 传"每帧真实时间"而不是 getGameTimeDeltaTicks()。
        // 后者只在发生 tick 的那一帧非零（20 tick/s 下约每 3 帧跳一次），会让后处理动画
        // 呈锯齿状推进，观感是"平滑一会儿、突变一下"，节拍约 1 秒。详见 ClientRenderCache#updateVeil。
        ClientRenderCache.INSTANCE.updateVeil(deltaTracker.getRealtimeDeltaTicks());
    }
}
