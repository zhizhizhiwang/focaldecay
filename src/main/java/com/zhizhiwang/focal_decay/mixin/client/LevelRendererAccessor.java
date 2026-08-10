package com.zhizhiwang.focal_decay.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LevelRenderer 的 viewArea，用于判断世界渲染器是否已就绪
 * （世界卸载/加载过渡期 viewArea 为 null，调用 setSectionDirty 会 NPE）。
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("viewArea")
    ViewArea focaldecay$getViewArea();
}
