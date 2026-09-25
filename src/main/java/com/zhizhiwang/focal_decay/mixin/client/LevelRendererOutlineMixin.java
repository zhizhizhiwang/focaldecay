package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 方块选择框（黑框）跟随失焦幽灵（BACKLOG `P1-7`，2026-09-25 新增，<b>默认关闭</b>）。
 * <p>
 * <b>问题</b>：本模组已经把方块<b>模型</b>、面剔除、挖掘速度/工具、中键选取都改成按
 * "可见目标"处理了，但那个黑色选择框画的还是<b>真实方块</b>的形状。
 * 后果是"看着是半砖、框却框住一整格"——它直接顶撞"所见即所得"这条取向，
 * 也是玩家最容易察觉的一处不一致（因为准星一直对着它）。
 * <p>
 * <b>注入点为什么选这里</b>：原版 {@code LevelRenderer#renderLevel} 里先
 * {@code BlockState blockstate = this.level.getBlockState(blockpos1)}，
 * 随后把它传给 {@code renderHitOutline(...)}。而 {@code renderHitOutline} 内部才去取
 * {@code state.getShape(...)}——那时候已经拿不到"真实方块"了，所以必须在上游那一行换掉。
 * 这个重定向与 {@code MinecraftPickBlockMixin} 是同一个套路（那处也是重定向
 * {@code ClientLevel#getBlockState}），两处保持同源，将来一起改。
 * <p>
 * <b>只影响渲染</b>：不碰碰撞箱、不碰射线命中（射线按真实方块是必须的——
 * 否则玩家会点到一个"客户端以为存在、服务端不认"的位置）。
 * 也就是说开了这个开关之后，"摸不到"的不一致<b>依然存在</b>，只是"看"这一侧统一了。
 * 这正是 `P1-7` 建议的取向：混合碰撞几乎不可能可靠，只做选择框，并把碰撞不一致写进手册。
 * <p>
 * <b>为什么做成开关而不是直接改</b>：这是主观可评估项，先在实机里看两种画法哪个更舒服，
 * 再决定默认值（见 {@code VERIFY-device-matrix.md} §3c）。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {

    /**
     * 把"选择框形状取自哪个状态"换成可见目标。
     * <p>
     * 开关关闭时原样返回真实状态——这样默认行为与原版**逐位相同**，
     * 不会因为装了这个模组就让没开开关的人看到不一样的选择框。
     */
    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState focaldecay$outlineUsesGhostShape(ClientLevel level, BlockPos pos) {
        BlockState real = level.getBlockState(pos);
        if (!FocalDecayConfig.OUTLINE_FOLLOWS_GHOST.get()) {
            return real;
        }
        // 统一入口：与渲染、面剔除、挖掘、中键选取同一个公式（含"没抽中/非源/受保护"的退回）
        return ClientRenderCache.INSTANCE.visibleState(level, pos);
    }
}
