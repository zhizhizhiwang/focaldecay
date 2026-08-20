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
        ClientRenderCache.INSTANCE.updateVeil(deltaTracker.getGameTimeDeltaTicks());
    }
}
