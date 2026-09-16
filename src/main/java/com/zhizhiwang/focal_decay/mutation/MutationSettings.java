package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;

/**
 * 失焦解析的<b>输入快照</b>（2026-09-17，联机一致性修复）。
 * <p>
 * {@link MutationHelper#resolve} 是服务端与客户端<b>共用同一个函数</b>：给定相同的输入，两端必然算出
 * 相同的结果。这个"必然"以前是靠约定维持的——函数体直接读 {@code FocalDecayConfig}（SERVER 配置）
 * 和调用方给的 {@code worldSeed}，而客户端的 SERVER 配置是它自己那份文件、种子在多人模式下干脆拿不到。
 * 于是"同一个函数"吃到了不同的输入，画面与真实转换就对不上了。
 * <p>
 * 本类把那组输入变成一个<b>显式、可序列化</b>的值对象：服务端用 {@link #fromConfig} 从自己的配置
 * 构造（权威），然后整份发给客户端；客户端只允许用收到的那一份。于是"两端一致"从约定变成了
 * 数据结构上不可能不一致——客户端拿不到服务端的快照时不渲染幽灵（见
 * {@code ClientRenderCache}），而不是拿自己的默认值猜一个。
 * <p>
 * <b>哪些东西属于本类、哪些不属于</b>：
 * <ul>
 *   <li>属于：一次解析里所有<b>静态</b>输入——世界种子、周期长度、阶段划分、每阶段概率、大池概率、
 *       阶段3语义锁定强度、引导完备度折半、候选体训练点数。它们只在配置变更时变，改一次全部重发即可。</li>
 *   <li>不属于：随游戏进程连续变化的量——末日天数、观测者是否在线、调试时钟（{@code SyncWorldDataPacket}），
 *       以及随玩家行为变化的量——原型机效果、方块诞生周期（{@code SyncRegionDataPacket}）。
 *       它们各有自己的同步通道，见 {@code PROXYAI.md §8}。</li>
 * </ul>
 * 公式本身仍然只有一份：本类的方法全部委托给 {@link MutationHelper} 里与之对应的静态纯函数，
 * 而 {@link MutationHelper#currentStage} / {@link MutationHelper#mutationChance} /
 * {@link MutationHelper#displayPeriod} 这些"读本端配置"的入口也走同一批静态纯函数。
 */
public record MutationSettings(
        long worldSeed,
        int baseInterval,
        boolean stageSystem,
        int stage2Day,
        int stage3Day,
        double chanceStage1,
        double chanceStage2,
        double chanceStage3,
        double wildChance,
        double semanticLockStage3,
        boolean guidedStage3Halve,
        int candidatePoints,
        int copyTrainPenalty) {

    /**
     * 从<b>本端配置</b>构造快照。服务端调用它得到权威值；客户端只在单人/集成服务器场景下用它兜底
     * （正常情况下客户端用的是服务端发来的那一份，见 {@code SyncMutationSettingsPacket}）。
     */
    public static MutationSettings fromConfig(long worldSeed) {
        return new MutationSettings(
                worldSeed,
                (int) MutationHelper.configBaseInterval(),
                FocalDecayConfig.ENABLE_STAGE_SYSTEM.get(),
                FocalDecayConfig.STAGE2_DAY.get(),
                FocalDecayConfig.STAGE3_DAY.get(),
                FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE1.get(),
                FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE2.get(),
                FocalDecayConfig.BLOCK_MUTATION_CHANCE_STAGE3.get(),
                FocalDecayConfig.WILD_CHANCE.get(),
                FocalDecayConfig.SEMANTIC_LOCK_STAGE3_STRENGTH.get(),
                FocalDecayConfig.GUIDED_STAGE3_HALVE.get(),
                FocalDecayConfig.CANDIDATE_REQUIRED_POINTS.get(),
                FocalDecayConfig.TOTAL_STABILITY_COPY_TRAIN_PENALTY.get());
    }

    /**
     * 覆盖每阶段概率（三个阶段同值）。<b>只给自测/基准用</b>：{@code chance = 1} 表示"每个周期必中"，
     * 这样采样数就等于周期数。游戏逻辑里不存在这种档位。
     */
    public MutationSettings withChance(double chance) {
        return new MutationSettings(worldSeed, baseInterval, stageSystem, stage2Day, stage3Day,
                chance, chance, chance, wildChance, semanticLockStage3, guidedStage3Halve,
                candidatePoints, copyTrainPenalty);
    }

    /** 当前阶段。 */
    public int stage(long days) {
        return MutationHelper.stageFor(days, stageSystem, stage2Day, stage3Day);
    }

    /** 当前阶段的每周期命中概率。 */
    public double blockChance(int stage) {
        return MutationHelper.chanceFor(stage, chanceStage1, chanceStage2, chanceStage3);
    }

    /** 显示刻（失焦解析用）：带调试倍率与偏移。 */
    public long displayPeriod(long gameTick, double speed, long offset) {
        return MutationHelper.scaledPeriod(gameTick, baseInterval, speed, offset);
    }

    /** 存储刻（诞生周期用）：永远走真实时间轴，与调试倍率无关。 */
    public long storagePeriod(long gameTick) {
        return MutationHelper.scaledPeriod(gameTick, baseInterval, 1.0, 0L);
    }

    /** 候选观测者练满所需训练点数（客户端用它复算"已练满 = 硬保护"）。 */
    public int requiredCandidatePoints(int copies) {
        return ObserverModelData.requiredCandidatePoints(copies, candidatePoints, copyTrainPenalty);
    }
}
