package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 在区块编译时把 targetCache 中的"突变目标"方块状态替换进网格，
 * 实现客户端预览（只改渲染、不改真实世界）。
 * <p>
 * 只劫持 {@code RenderChunkRegion.getBlockState} 一处即可覆盖编译期的全部读取：
 * <ul>
 *   <li>方块模型与流体渲染；</li>
 *   <li>邻居面的剔除判定 —— {@code Block.shouldRenderFace} 内部走 {@code level.getBlockState}，
 *       而这里的 {@code level} 就是 {@link RenderChunkRegion}。</li>
 * </ul>
 * <p>
 * 曾短暂加过一处 {@code BlockState.isSolidRender} 的重定向（想让"雪片突变成完整方块"后也能参与剔除），
 * 但那会带来不一致：目标形状与原始形状不同时可能把真正可见的邻面剔掉。
 * 那个"雪下面的方块没突变"的现象，真正成因在 {@code ClientRenderCache.isExposed} 的判据，不在这里，
 * 所以该重定向已移除。
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    /**
     * 方块循环里的状态读取。运行时 RebuildTask 调用的是带 additionalRenderers 的 5 参数 compile
     * （4 参数版本只是委托），实际方块循环与 getBlockState 调用都在 5 参数版本里。
     */
    @Redirect(
            method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private static BlockState focaldecay$replaceBlockState(RenderChunkRegion region, BlockPos pos) {
        BlockState original = region.getBlockState(pos);
        return ClientRenderCache.INSTANCE.resolve(region, pos, original);
    }
}
