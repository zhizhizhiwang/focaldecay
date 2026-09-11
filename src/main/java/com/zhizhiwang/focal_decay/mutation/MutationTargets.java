package com.zhizhiwang.focal_decay.mutation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 突变目标抽取的**唯一入口**（2026-09-11 抽象）。
 * <p>
 * 需要"当前可见失焦目标"的三处地方——世界（锚/原型机固化范围）、交互（左键锁定、右键训练）、
 * 渲染预览——都通过本类解析，避免各自实现：
 * <pre>
 *   1. 转换源判定：空气 / 带方块实体 / 黑名单 / 非完整立方体（门、楼梯、栅栏等）→ 直接返回原方块；
 *   2. {@link MutationHelper#getVisibleTarget}：硬/软保护 → 诞生周期 → 概率 → 引导偏向 → 累积回退扫描。
 * </pre>
 * 此前右键训练漏了第 1 步，导致右键不完整方块会记录一个"并不存在的突变目标"。
 */
public final class MutationTargets {

    private MutationTargets() {
    }

    /**
     * 纯函数入口：调用方提供上下文（池、概率、保护、引导偏向、周期等），服务端与客户端共用同一公式。
     */
    public static BlockState resolve(BlockGetter level, BlockState state, BlockPos pos, int stage, long worldSeed,
                                     long period, List<Block> pool, double chance, GuidedBias bias,
                                     MutationHelper.Protection protection, long birthPeriod) {
        if (!MutationHelper.isConversionSource(state, level, pos, stage)) {
            return state; // 非转换源：永不失焦，所见即原方块
        }
        return MutationHelper.getVisibleTarget(state, pos, worldSeed, period, pool, chance, bias,
                protection, birthPeriod);
    }

    /** 服务端入口：自行装配该维度的阶段/观察者状态、池、保护、引导偏向与周期。 */
    public static BlockState resolveServer(ServerLevel level, BlockPos pos, BlockState state) {
        if (FocalDecayWorldData.get(level.getServer()).isObserverOnline()) {
            return state; // 失焦终止：无可突变目标
        }
        int stage = MutationHelper.currentStage(FocalDecayWorldData.get(level.getServer()).getDays());
        if (!MutationHelper.isConversionSource(state, level, pos, stage)) {
            return state;
        }
        MutationPoolManager manager = MutationPoolManager.get(level);
        return MutationHelper.getVisibleTarget(state, pos, level.getSeed(),
                MutationHelper.blockPeriod(level.getGameTime()),
                manager.getGlobalPool().snapshot(), MutationHelper.mutationChance(stage),
                manager.getGuidedBias(pos, state, stage), manager.protectionInfo(pos, state, stage),
                manager.getBlockBirthPeriod(pos));
    }
}
