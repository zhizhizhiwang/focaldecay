package com.zhizhiwang.focal_decay.mixin.client;

import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 RenderChunkRegion 持有的 Level 引用，供区块编译线程读取世界状态。
 */
@Mixin(RenderChunkRegion.class)
public interface RenderChunkRegionAccessor {
    @Accessor("level")
    Level focaldecay$getLevel();
}
