package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 中键选取（pick block）支持：把拾取目标从真实方块换成"失焦预览"的目标方块。
 * 注入点为 Minecraft#pickBlock 中唯一两处 ClientLevel#getBlockState 调用
 * （主获取 + 空物品告警分支），统一返回可见状态。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPickBlockMixin {

    @Redirect(
            method = "pickBlock()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private static BlockState focaldecay$pickGhostState(ClientLevel level, BlockPos pos) {
        return ClientRenderCache.INSTANCE.visibleState(level, pos);
    }
}
