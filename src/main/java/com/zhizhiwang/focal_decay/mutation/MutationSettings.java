package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;

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
 *       阶段3语义锁定强度、引导完备度折半、引导跨级概率、候选体训练点数。它们只在配置变更时变，改一次全部重发即可。</li>
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
        /**
         * {@code wild_auto_include}：构建突变查表时是否把"所有 cube 且无方块实体"的方块自动纳入大池。
         * 它决定<b>池的成员</b>，因此也决定每一个目标——两端必须同值。
         * 2026-09-30 之前它不在快照里，两端各读自己的配置（BACKLOG `P0-7`）。
         */
        boolean wildAutoInclude,
        /**
         * {@code guide_up_tier_chance}：引导 / 催化下允许"跨一级"的概率（{@code DESIGN.md} §13.9）。
         * 它进入 {@link MutationHelper#resolve}，所以必须跟着快照走——否则两端会算出不同的目标。
         */
        double upTierChance) {

    /**
     * 从<b>本端配置</b>构造快照。服务端调用它得到权威值；客户端只在单人/集成服务器场景下用它兜底
     * （正常情况下客户端用的是服务端发来的那一份，见 {@code SyncMutationSettingsPacket}）。
     */
    public static MutationSettings fromConfig(long worldSeed) {
        MutationSettings settings = new MutationSettings(
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
                FocalDecayConfig.WILD_AUTO_INCLUDE.get(),
                FocalDecayConfig.GUIDE_UP_TIER_CHANCE.get());
        // 池成员也走同一份取值（它决定每一个目标，两端必须同值）。
        MutationIndexes.setWildAutoInclude(settings.wildAutoInclude());
        return settings;
    }

    /**
     * 服务端权威快照的进程级缓存。
     * <p>
     * <b>为什么服务端也要用快照，而不是直接读配置</b>（2026-09-30，BACKLOG `P0-7`）：
     * 两端一致的前提是"喂进 {@link MutationHelper#resolve} 的输入一样"，而客户端的输入
     * <b>只可能</b>来自同步下来的快照。服务端如果各处直接读 {@code FocalDecayConfig}，
     * 就存在两条取值的路径——本次改动之前 {@code wildChance} / {@code semanticLockStage3}
     * 恰好就是"服务端读配置、客户端读快照"。单人环境下两者天然相同，所以从没暴露；
     * 而局域网里客户端改过自己的 toml 就会分叉（这正是 §13.12 修过的那一类）。
     * 把服务端也统一到同一个快照，这种分叉就<b>结构上不可能</b>。
     * <p>
     * 缓存而不是每次重新读：{@code resolve} 在热路径上，而且这样能保证"同一次解析过程里看到的配置是同一份"。
     * 配置重载（{@code ModConfigEvent.Reloading}）时必须丢弃它，见 {@link #invalidateServerCache()}。
     */
    private static volatile MutationSettings serverCache;

    /** 服务端的权威快照（首次访问时从配置构造）。 */
    public static MutationSettings server(long worldSeed) {
        MutationSettings cached = serverCache;
        if (cached == null) {
            cached = fromConfig(worldSeed);
            serverCache = cached;
        }
        return cached;
    }

    /** 配置重载后丢弃缓存，下次访问重建。 */
    public static void invalidateServerCache() {
        serverCache = null;
    }

    /**
     * 覆盖每阶段概率（三个阶段同值）。<b>只给自测/基准用</b>：{@code chance = 1} 表示"每个周期必中"，
     * 这样采样数就等于周期数。游戏逻辑里不存在这种档位。
     */
    public MutationSettings withChance(double chance) {
        return new MutationSettings(worldSeed, baseInterval, stageSystem, stage2Day, stage3Day,
                chance, chance, chance, wildChance, semanticLockStage3, guidedStage3Halve,
                wildAutoInclude, upTierChance);
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
}
