package com.zhizhiwang.focal_decay.data;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ImpossibleTrigger;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.advancements.AdvancementSubProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.function.Consumer;

/**
 * 隐藏 advancement：玩家首次进入世界时发下《失焦事件现场作业手册》。
 * <p>
 * Patchouli 没有"进服送书"的原生功能，官方推荐做法正是这种隐藏 advancement +
 * advancement_reward 战利品表。触发器用 {@code minecraft:tick}（{@link PlayerTrigger.TriggerInstance#tick()}）——
 * 它在玩家存在于世界中的第一个 tick 就满足，**与背包内容无关**，因此创造模式新世界同样会发书。
 * （早期版本误用 {@code has_items}（需要背包里有泥土），导致新世界永远拿不到书。）
 * 奖励只发一次，发放后 advancement 永久完成，不会重复给书。
 * <p>
 * 战利品表是<b>手写资源</b>（{@code data/focal_decay/loot_table/grant_observer_manual.json}），
 * 不走数据生成：{@code LootTableProvider} 写盘时统一用 vanilla 的 {@code LootTable.DIRECT_CODEC}
 * 重新编码，会把条件键丢掉（实测确认）。
 * 该表用自定义的 vanilla 战利品条件 {@code focal_decay:patchouli_loaded}
 * （见 {@link ModLootConditions}）做前置门控——**未安装 Patchouli 时表为空**，
 * 不会留下一个打不开的空书；而 advancement 也不会被判完成（条件不满足 → 池被丢弃），
 * 所以玩家之后装上 Patchouli 仍能收到书。
 */
public final class ModAdvancementProvider implements AdvancementSubProvider {

    /** advancement id：{@code data/focal_decay/advancement/grant_observer_manual.json}。 */
    public static final ResourceLocation GRANT_MANUAL =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "grant_observer_manual");

    /**
     * 手册条目锁的 advancement id（王座协议卷 / 重聚焦卷）。
     * <p>
     * 用 <b>{@code minecraft:impossible}</b> 触发器：它的 {@code addPlayerListener} 是空实现，
     * 因此在游戏里<b>永远不会自行满足</b>，锁的推进完全由代码控制（抵达王座附近，或
     * {@code /focaldecay unlock} 指令）——通过 {@link #award} 显式授予。
     * <p>
     * 这两个 advancement 都没有 {@code display}，所以在进度界面里不可见。
     * <p>
     * ⚠️ 别改成 {@code minecraft:tick}：那个触发器由服务端每 tick 主动触发（也就是送书用的那个），
     * 若拿它当锁，锁会在玩家第一次 tick 时自己解开。
     */
    public static final ResourceLocation UNLOCK_THRONE =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "unlock_throne");
    public static final ResourceLocation UNLOCK_CORE =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "unlock_core");

    /** 条件名：与 {@link #award} 配合使用。 */
    public static final String UNLOCK_CRITERION = "unlocked";

    /** 手册发放表的键（手写资源，见类注释）。 */
    public static final ResourceKey<LootTable> GRANT_OBSERVER_MANUAL = ResourceKey.create(
            Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "grant_observer_manual"));

    @Override
    public void generate(HolderLookup.Provider registries, Consumer<AdvancementHolder> output) {
        output.accept(Advancement.Builder.advancement()
                .addCriterion("tick", PlayerTrigger.TriggerInstance.tick())
                .rewards(AdvancementRewards.Builder.loot(GRANT_OBSERVER_MANUAL))
                .build(GRANT_MANUAL));

        output.accept(unlock(UNLOCK_THRONE));
        output.accept(unlock(UNLOCK_CORE));
    }

    /** 一个无 display 的解锁 advancement（见 {@link #UNLOCK_THRONE} 的说明）。 */
    private static AdvancementHolder unlock(ResourceLocation id) {
        return Advancement.Builder.advancement()
                .addCriterion(UNLOCK_CRITERION, new Criterion<>(CriteriaTriggers.IMPOSSIBLE,
                        new ImpossibleTrigger.TriggerInstance()))
                .build(id);
    }

    /** 授予解锁条件。返回 true 表示本次确实改变了状态（此前未解锁）。 */
    public static boolean award(ServerPlayer player, ResourceLocation advancementId) {
        AdvancementHolder holder = player.server.getAdvancements().get(advancementId);
        return holder != null && player.getAdvancements().award(holder, UNLOCK_CRITERION);
    }

    /**
     * 是否已解锁。
     * <p>
     * 刻意只读<b>服务端</b>的进度：不要用 Patchouli 的 {@code ClientAdvancements.hasDone()}，
     * 它在 {@code Minecraft.getInstance().getConnection()} 为 null 时（服务端环境）会 NPE。
     */
    public static boolean isUnlocked(ServerPlayer player, ResourceLocation advancementId) {
        AdvancementHolder holder = player.server.getAdvancements().get(advancementId);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }
}
