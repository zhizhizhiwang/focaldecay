package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FocalDecay.MODID);

    public static final DeferredBlock<AnchorPrototypeBlock> ANCHOR_PROTOTYPE = BLOCKS.register("anchor_prototype", AnchorPrototypeBlock::new);
    public static final DeferredBlock<TrainingTerminalBlock> TRAINING_TERMINAL = BLOCKS.register("training_terminal", TrainingTerminalBlock::new);
    public static final DeferredBlock<ObserverCoreBlock> OBSERVER_CORE = BLOCKS.register("observer_core", ObserverCoreBlock::new);
    public static final DeferredBlock<ThroneBlock> THRONE_BLOCK = BLOCKS.register("throne_block", ThroneBlock::new);
    /**
     * 语义催化剂（2026-09-29，玩法主轴 P2-7 第③步）。
     * <p>
     * <b>刻意与原型机分开</b>：原型机是"稳定"装置（保护一片区域），催化剂是"点火"装置
     * （把一片区域的失焦从概率变成必然）。两者的方块、合成与心理位置都不同，
     * 但它们共用同一条区域效果同步管线——复用管线是对的，复用一个方块不是。
     */
    public static final DeferredBlock<CatalystBlock> SEMANTIC_CATALYST =
            BLOCKS.register("semantic_catalyst", CatalystBlock::new);

    // ---- 仅用于自测的探针方块（BACKLOG P0-4，作者 2026-09-25 裁定允许注册） ----
    // 刻意**不**配套 BlockItem、不进创造栏、没有配方：玩家在正常游戏里拿不到它们，
    // 只有 /focaldecay mutation selftest 的 [break] 段会摆两块。
    // 没有 BlockItem 还有一个副作用是我们要的：MutationIndex 的池按 asItem() != AIR 过滤，
    // 所以这两块不会被"方块池 ↔ 物品池"的自动登记收进失焦候选（否则测试方块会污染玩法）。
    /**
     * 扮演"玩家看到的真实方块"（旧世界里的源方块，它会被换成目标）。
     * <p>
     * 材质刻意用<b>白色混凝土</b>（目标用<b>黄绿色混凝土</b>）而不是任何真实的失焦目标材质：
     * 实机做"模组方块兼容性"回归时要能一眼认出屏幕上哪一格是探针，
     * 用石头材质会与真实方块混在一起、看不出测的是谁。
     * 方块状态与模型是手写资源（本项目不生成 {@code blockstates/}），
     * 漏了只会在客户端日志里留一行 {@code missing model for variant} 警告——那正是踩过的坑。
     */
    public static final DeferredBlock<BreakProbeBlock> BREAK_PROBE_SOURCE =
            BLOCKS.register("break_probe_source", () -> new BreakProbeBlock(false));
    /** 扮演"失焦解析出来的可见目标"，即重派发之后被真正破坏的那一块。 */
    public static final DeferredBlock<BreakProbeBlock> BREAK_PROBE_TARGET =
            BLOCKS.register("break_probe_target", () -> new BreakProbeBlock(true));

    private ModBlocks() {
    }
}
