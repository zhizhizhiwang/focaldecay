package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块突变目标的**服务端入口**（2026-09-11 抽象，2026-09-15 重写）。
 * <p>
 * 需要"当前可见失焦目标"的服务端位置——世界（锚/原型机固化范围）、交互（左键锁定、右键训练、
 * 右键交互）——都通过本类装配上下文，避免各自实现。装配的就是这几样东西：
 * <pre>
 *   阶段与概率 → 本维度的突变查表 → 引导偏向 → 保护形态 → 诞生周期
 * </pre>
 * 真正的抽取公式只有一份，在 {@link MutationHelper#resolve}；客户端预览走同一个函数，
 * 只是上下文由 {@code ClientRenderCache} 自己装配。源门控（非转换源直接返回原方块）
 * 也在那一个函数里，因此不存在"某条路径漏判"的可能。
 */
public final class MutationTargets {

    private MutationTargets() {
    }

    /** 服务端入口：自行装配该维度的阶段/观察者状态、突变查表、保护、引导偏向与周期。 */
    public static BlockState resolveServer(ServerLevel level, BlockPos pos, BlockState state) {
        return resolveServer(level, pos, state, MutationEventHandler.displayPeriodIndex(level));
    }

    /**
     * 服务端入口（<b>周期由调用方给定</b>）：交互路径用客户端回报的显示刻
     * （{@code InteractionHandler#interactionPeriod}），其余场合用服务端自己的那根指针。
     * <p>
     * 为什么要让交互路径吃客户端的刻度：玩家操作的是"他看到的东西"。客户端的
     * {@code gameTime} 是本地自走的，服务端每 20 tick 才校一次，掉帧时两者会差出一个周期，
     * 用服务端的周期解析就等于把他正在挖的那个方块换掉。
     */
    public static BlockState resolveServer(ServerLevel level, BlockPos pos, BlockState state, long periodIndex) {
        if (FocalDecayWorldData.get(level.getServer()).isObserverOnline()) {
            return state; // 失焦终止：无可突变目标
        }
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        MutationIndex index = MutationIndexes.get(level.dimension());
        MutationPoolManager manager = MutationPoolManager.get(level);
        return MutationHelper.resolve(state, pos, level.getSeed(),
                periodIndex, index,
                MutationHelper.mutationChance(stage), manager.getGuidedBias(pos, state, stage),
                manager.protectionInfo(pos, state, stage), manager.getBlockBirthPeriod(pos));
    }
}
