package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 面剔除的邻居读取也要走幽灵世界（2026-09-16）。
 * <p>
 * <b>修的是什么</b>：{@code SectionCompilerMixin} 只把"方块循环里那一次
 * {@code RenderChunkRegion.getBlockState}"替换掉了，而面剔除的邻居状态是在
 * <b>另一个方法里</b>读的——{@code Block#shouldRenderFace} 内部的
 * {@code level.getBlockState(neighborPos)}。那处没有被替换，于是剔除判定拿到的是<b>真实</b>邻居，
 * 而网格里画的是<b>幽灵</b>邻居，两边对不上：
 * <ul>
 *   <li>真实石头（幽灵玻璃）旁边的方块，看到的邻居是"石头"——不透明——于是它朝玻璃的那一面被剔掉。
 *       可那一面在视觉上是"不透明方块挨着玻璃"，原版是要画的。结果就是透过玻璃能看进方块内部
 *       （背面又被剔除），看起来像世界破了个洞。</li>
 *   <li>反过来，真实玻璃（幽灵石头）旁边的方块也会因为"邻居不透明"而被多剔或少剔。</li>
 * </ul>
 * 换句话说：<b>网格用的是幽灵状态，剔除用的是真实状态</b>，玻璃把两者的差异放大到了肉眼可见。
 * <p>
 * <b>为什么改这一处就够</b>：{@code Block#shouldRenderFace} 里读邻居只有这一个调用点，
 * 而且它是所有模型面剔除的唯一判据（{@code ModelBlockRenderer} 的两个 {@code tesselateBlock}
 * 重载都调它）。替换之后，剔除比较的两个状态就都是幽灵状态，
 * 同时 {@code OCCLUSION_CACHE}（按 {@code (本方状态, 邻居状态, 面)} 三元组缓存）的键也跟着变成幽灵对，
 * 不会出现"用真实状态的缓存结果去判断幽灵"的污染。
 * <p>
 * <b>代价</b>：面剔除每次查询都要过一次缓存查找（绝大多数是 O(1) 命中，见
 * {@code ClientRenderCache#ghostState}）。所以只在 {@code level} 确实是
 * {@link RenderChunkRegion}（即区块编译路径）时才介入；破坏粒子、手持方块、预览等
 * 走 {@code ClientLevel} 的地方一行都不改。
 * <p>
 * <b>没有覆盖的地方</b>：环境光遮蔽（{@code ModelBlockRenderer} 里那 12 次邻居读取）
 * 仍然用真实状态，所以幽灵附近的光影过渡会有一点偏差。那属于观感而非破洞，
 * 而且每方块要多 12 次查找，收益不划算，故有意不改。
 */
@Mixin(Block.class)
public abstract class BlockShouldRenderFaceMixin {

    @Redirect(
            method = "shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private static BlockState focaldecay$ghostNeighbor(BlockGetter level, BlockPos pos) {
        BlockState real = level.getBlockState(pos);
        if (!(level instanceof RenderChunkRegion region)) {
            return real; // 非区块编译路径（粒子/手持/预览）：保持原版语义
        }
        return ClientRenderCache.INSTANCE.ghostState(region, pos, real);
    }
}
