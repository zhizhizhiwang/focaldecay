package com.zhizhiwang.focal_decay.mixin;

import com.zhizhiwang.focal_decay.mutation.MutationTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 服务端的挖掘进度门槛按<b>可见目标</b>算（2026-09-26，实机发现的问题）。
 * <p>
 * <b>问题（作者实机观察到的"挖掘掉落滞后"）</b>：挖一个"因为突变而变软"的方块时，
 * 方块会在客户端消失之后过一小会儿才真的掉东西。
 * <p>
 * <b>根因是两端拿了不同的硬度</b>：
 * <ul>
 *   <li><b>客户端</b>{@code MultiPlayerGameMode#continueDestroyBlock} 里
 *       {@code blockstate.getDestroyProgress(...)} 已经被 {@code MultiPlayerGameModeMixin}
 *       重定向到<b>幽灵</b>状态（那是早就做好的"挖掘速度按可见目标"），
 *       所以进度按幽灵硬度累积，满了就发 {@code STOP_DESTROY_BLOCK}；</li>
 *   <li><b>服务端</b>{@code ServerPlayerGameMode#handleBlockBreakAction} 的 STOP 分支用的是
 *       {@code this.level.getBlockState(pos)}，也就是<b>真实方块</b>的硬度，
 *       判定门槛还是 0.7（不是 1.0）：
 *       <pre>float f1 = blockstate1.getDestroyProgress(...) * (j + 1);
 * if (f1 &gt;= 0.7F) { 真正破坏 } else { hasDelayedDestroy = true; ... }</pre></li>
 * </ul>
 * 幽灵更软时客户端<b>早</b>发停止包，服务端按真实（更硬）硬度算出来不到 0.7 →
 * 掉进 {@code hasDelayedDestroy} 延迟路径 → 掉落滞后。幽灵更硬时反向出错：
 * 服务端会在客户端还没挖完时就判定破坏。
 * <p>
 * <b>修法</b>：把服务端这三处进度计算也改成"按玩家看到的那个方块算"，
 * 与客户端同一个口径。这符合本模组"所见即所得"的取向——
 * 玩家挖的是他看到的那一块，那么"挖动它需要多久"两端必须用同一个答案。
 * <p>
 * <b>为什么用 {@code @Redirect} 而不是把方块换进世界</b>：这里只是<b>一次判定</b>，
 * 不是一次操作。换方块会真的改世界（那正是破坏路径重构里做的，因为那里要跑完整原版管线），
 * 而这里换完还得换回来，中途的邻块更新、方块实体、别的模组的钩子全都要处理——
 * 代价远大于收益。所以只替换这一个调用的返回值。
 * <p>
 * <b>作用于全部三处调用</b>（起始判定 / STOP 判定 / 延迟破坏路径）：
 * `@Redirect` 会替换方法内所有匹配的 `INVOKE`。这正是我们要的——
 * 三处若用不同硬度，就会重现同一类不一致（只是换个分支）。
 * <p>
 * <b>退回原版的条件</b>：{@link MutationTargets#resolveServer} 对非转换源、没抽中、
 * 观察者在线、受保护的位置都返回<b>原方块</b>，此时本重定向与原版逐位相同。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    @Final
    private ServerPlayer player;

    @Shadow
    private ServerLevel level;

    /**
     * 把"这个方块的挖掘进度增量"换成"玩家看到的那个方块的"。
     *
     * @param state  原版本会使用的状态（真实方块）
     * @param pos    正在挖的位置
     * @return 可见目标状态的挖掘进度增量；没有失焦时就是原版行为
     */
    @Redirect(
            method = "handleBlockBreakAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"
            )
    )
    private float focaldecay$visibleDestroyProgress(BlockState state, net.minecraft.world.entity.player.Player p,
                                                    net.minecraft.world.level.BlockGetter getter, BlockPos pos) {
        // 自测/异常路径保护：没有玩家或没有世界时退回原版，不要在这里抛异常
        // （挖矿是每秒都在跑的路径，抛一次就是一个崩溃报告）
        if (player == null || level == null) {
            return state.getDestroyProgress(p, getter, pos);
        }
        BlockState visible = MutationTargets.resolveServer(level, pos, state);
        // 可见目标就是原方块（非源/没抽中/观察者在线/受保护）时，这一步与原版完全等价。
        return visible.getDestroyProgress(p, getter, pos);
    }
}
