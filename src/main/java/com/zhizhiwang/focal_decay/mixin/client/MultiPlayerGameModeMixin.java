package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 挖掘进度 / 工具要求由"当前可见的失焦目标"决定（而非原方块）：
 * 把 {@code continueDestroyBlock} 里读方块状态的调用改为读可见目标（走缓存，O(1)）。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Redirect(method = "continueDestroyBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState focaldecay$visibleStateForMining(ClientLevel level, BlockPos pos) {
        return ClientRenderCache.INSTANCE.miningState(level, pos);
    }
}
