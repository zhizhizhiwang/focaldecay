package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 交互路径的客户端侧（两块职责）：
 * <ol>
 *   <li><b>挖掘进度 / 工具要求</b>由"当前可见的失焦目标"决定（而非原方块）：
 *       把 {@code continueDestroyBlock} 里读方块状态的调用改为读可见目标（走缓存，O(1)）。</li>
 *   <li><b>回报显示刻</b>（2026-09-17）：左键开始挖掘、右键交互时，把"我这一眼的失焦刻"
 *       发给服务端，让服务端按<b>玩家看到的那一刻</b>解析目标（见
 *       {@code SyncClientViewPacket} 与 {@code InteractionHandler#interactionPeriod}）。
 *       注入点选在方法 HEAD：原版紧接着就发出交互包，两条包在同一条连接上顺序到达，
 *       服务端处理交互时回报一定已经在手上了。</li>
 * </ol>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Redirect(method = "continueDestroyBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState focaldecay$visibleStateForMining(ClientLevel level, BlockPos pos) {
        return ClientRenderCache.INSTANCE.miningState(level, pos);
    }

    /** 挖掘开始（{@code START_DESTROY_BLOCK} 之前）：回报显示刻。 */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void focaldecay$reportMiningPeriod(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        // 创造模式也报：创造挖掘不转换，但同一只手上的右键转换是照样生效的（见 InteractionHandler）
        ClientRenderCache.INSTANCE.reportClientView(pos);
    }

    /** 右键交互（{@code ServerboundUseItemOnPacket} 之前）：回报显示刻。 */
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void focaldecay$reportUsePeriod(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        // 只报主手：服务端也只在主手上做转换，副手多报一次只是重复
        if (hand == InteractionHand.MAIN_HAND) {
            ClientRenderCache.INSTANCE.reportClientView(hitResult.getBlockPos());
        }
    }
}
