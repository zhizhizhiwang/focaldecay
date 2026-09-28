package com.zhizhiwang.focal_decay.mixin.client;

import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 通知失焦预览："这一格的<b>真实方块</b>变了，需要重扫它所在的区块节"（<b>P1-5</b>，2026-09-26）。
 * <p>
 * <b>为什么必须有这条通知</b>：幽灵是<b>真实方块的函数</b>，而真实方块变化的到达方式只有两种：
 * <ol>
 *   <li>玩家自己挖/放 —— 走 {@code InteractionHandler}，那条路径两端都会处理；</li>
 *   <li><b>别人</b>改的、或爆炸/流体改的 —— 客户端只会在 {@link ClientLevel#setBlock} 里看到。</li>
 * </ol>
 * 在"扫描器从不空闲"的旧实现下，第 2 种情况会被下一次整体重扫兜住（虽然浪费，但正确）。
 * 而 P1-5 把整体重扫去掉了，所以**必须**在这里显式通知，否则：
 * <blockquote>
 * 别人在我旁边放了一块"会成为突变候选"的方块，而那一节我早就扫过了 →
 * 它永远不会被重新扫描 → 那一格不显示幽灵。
 * </blockquote>
 * 也就是说：<b>这条通知是"扫描器可以空闲"这个优化的正确性前提</b>，
 * 不是可选的锦上添花。
 * <p>
 * <b>为什么注入 {@code setBlock} 而不是某个包处理器</b>：{@code ClientLevel#setBlock} 是
 * 所有客户端方块变更的<b>唯一汇聚点</b>——服务端包、爆炸预测、本地放置最终都经过它。
 * 挂在包处理器上会漏掉本地预测路径。
 * <p>
 * <b>代价</b>：每次方块变化多一次 {@code SectionPos.asLong} 与一次并发集合插入。
 * 方块变化本来就是低频事件，相对于"每 tick 扫 49,152 个位置"可以忽略。
 * 通知本身<b>不做任何方块读取或缓存修改</b>（那是主线程扫描器的工作），
 * 所以从网络线程调用也是安全的。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSetBlockMixin {

    @Inject(method = "setBlock", at = @At("HEAD"))
    private void focaldecay$notifyBlockChanged(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                               CallbackInfoReturnable<Boolean> cir) {
        ClientRenderCache.INSTANCE.onBlockChanged(pos);
    }
}
