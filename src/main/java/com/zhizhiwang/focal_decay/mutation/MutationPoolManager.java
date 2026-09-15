package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 维度级原型机效果与方块诞生周期（设计大纲 §4.2 / §4.3）。
 * <p>
 * 2026-09-15 起本类<b>不再持有全局突变池</b>：池完全由数据包标签决定，运行时形态是
 * {@link MutationIndex}（见 {@code mutation.pool} 包），因此这里只剩两件真正需要持久化的事：
 * 有效原型机效果（瞬态，由方块实体在加载/换模时重建）与玩家放置方块的诞生周期。
 * <p>
 * 模型效果在<b>登记时</b>就把两样东西算好，之后逐方块查询不再碰标签和字符串：
 * <ul>
 *   <li>{@code trainedBlocks}——语义锁定的"被训练目标"集合。原来每方块都要
 *       {@code getKey(state.getBlock()).toString()} 再把字符串拿去 {@code List.contains}，
 *       等于在扫描热路径上逐方块分配一个字符串；</li>
 *   <li>{@code conceptPool}——引导模型的概念邻域（按形态类切分、缓存好的候选池）。</li>
 * </ul>
 */
public class MutationPoolManager extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_mutation_pool";
    private static final String TAG_BIRTHS = "Births";

    /**
     * 有效原型机效果：中心、切比雪夫半径、模型训练数据，外加两份登记期预算好的查表。
     *
     * @param trained 语义锁定的被训练方块（O(1) 命中判定）
     * @param concept 引导模型的概念邻域（null = 非引导模型或概念无效）
     */
    public record PrototypeEffect(BlockPos center, int radius, ObserverModelData data,
                                  Set<Block> trained, ClassifiedPool concept) {
    }

    private final List<PrototypeEffect> prototypeEffects = new ArrayList<>();
    /** 玩家放置方块的"诞生周期"（位置 -> 放置时的 periodIndex）。 */
    private final Map<BlockPos, Long> blockBirthPeriods = new HashMap<>();

    // ---- 工厂 ----
    public static final Factory<MutationPoolManager> FACTORY = new Factory<>(
            MutationPoolManager::new,
            MutationPoolManager::load,
            null
    );

    private MutationPoolManager() {
    }

    public static MutationPoolManager get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static MutationPoolManager load(CompoundTag tag, HolderLookup.Provider registries) {
        MutationPoolManager manager = new MutationPoolManager();
        manager.blockBirthPeriods.clear();
        ListTag birthsTag = tag.getList(TAG_BIRTHS, Tag.TAG_COMPOUND);
        for (int i = 0; i < birthsTag.size(); i++) {
            CompoundTag entry = birthsTag.getCompound(i);
            manager.blockBirthPeriods.put(BlockPos.of(entry.getLong("Pos")), entry.getLong("Period"));
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag birthsTag = new ListTag();
        for (Map.Entry<BlockPos, Long> entry : blockBirthPeriods.entrySet()) {
            CompoundTag birth = new CompoundTag();
            birth.putLong("Pos", entry.getKey().asLong());
            birth.putLong("Period", entry.getValue());
            birthsTag.add(birth);
        }
        tag.put(TAG_BIRTHS, birthsTag);
        return tag;
    }

    // ---- 原型机效果 ----
    public List<PrototypeEffect> getPrototypeEffects() {
        return prototypeEffects;
    }

    /**
     * 原型机模型变化时更新效果：有有效模型（非空白）则加入/更新，否则移除。
     * 模型半径：语义锁定/引导 = 基础半径，生物稳定 +4，完全稳定固定 32。
     * 登记期顺带把该模型用得上的查表算好（被训练方块集合、概念邻域池）。
     */
    public void updatePrototypeEffect(ServerLevel level, BlockPos pos, ItemStack modelStack) {
        prototypeEffects.removeIf(e -> e.center().equals(pos));
        ObserverModelData data = modelStack.getItem() instanceof ObserverModelItem
                ? ObserverModelItem.getData(modelStack) : null;
        if (data == null || ObserverModelData.TYPE_BLANK.equals(data.type())) {
            return;
        }
        MutationIndex index = MutationIndexes.get(level.dimension());
        prototypeEffects.add(new PrototypeEffect(pos.immutable(), radiusFor(data), data,
                parseTrained(data.trainedTargets()), conceptPool(data, index)));
    }

    public void removePrototypeEffect(BlockPos pos) {
        prototypeEffects.removeIf(e -> e.center().equals(pos));
    }

    private static Set<Block> parseTrained(List<String> trainedTargets) {
        Set<Block> blocks = new HashSet<>();
        for (String id : trainedTargets) {
            try {
                blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)));
            } catch (Exception ignored) {
                // 非法 ID 忽略（与训练期一致）
            }
        }
        return blocks;
    }

    /** 引导模型的概念邻域池；非引导模型或概念无效时返回 null。 */
    private static ClassifiedPool conceptPool(ObserverModelData data, MutationIndex index) {
        if (!ObserverModelData.TYPE_GUIDED.equals(data.type()) || data.concept().isEmpty()) {
            return null;
        }
        ClassifiedPool pool = index.tagged(data.concept());
        return pool.isEmpty() ? null : pool;
    }

    /**
     * 模型效果半径（供放置固化与效果注册共用）：生物稳定 +4，完全稳定按复制代数递减。
     * <p>
     * 完全稳定模型是唯一"可复制"的顶级保护，所以它必须随代数变弱，否则复制就没有代价：
     * 原件 32，一代副本 32 − p，二代副本 32 − 3p（p = {@code total_stability_copy_penalty}，
     * 递减量逐代递增，体现"越重铸越失真"）。
     */
    public static int radiusFor(ObserverModelData data) {
        // 防御：物品没有模型数据时不能假定它有。否则 data.type() 直接 NPE，
        // 而调用点（放基座、固化范围）都在玩家操作路径上，会直接崩游戏。
        if (data == null) {
            return FocalDecayConfig.PROTOTYPE_RADIUS.get();
        }
        if (ObserverModelData.TYPE_CANDIDATE.equals(data.type())) {
            // 候选观测者完成训练后兼具完全稳定效果（半径 32）；未完成 = 无保护（基础半径）
            return data.progress() >= ObserverModelItem.requiredCandidatePoints(data) ? 32
                    : FocalDecayConfig.PROTOTYPE_RADIUS.get();
        }
        return switch (data.type()) {
            case ObserverModelData.TYPE_BIO -> FocalDecayConfig.PROTOTYPE_RADIUS.get() + 4;
            case ObserverModelData.TYPE_TOTAL -> totalStabilityRadius(data.copies());
            default -> FocalDecayConfig.PROTOTYPE_RADIUS.get();
        };
    }

    /** 完全稳定模型的半径：32 减去年限递增的复制损耗，并不低于基础半径。 */
    private static int totalStabilityRadius(int copies) {
        int base = 32;
        int penalty = Math.max(0, FocalDecayConfig.TOTAL_STABILITY_COPY_PENALTY.get());
        int generation = Math.max(0, copies);
        // 三角数：1 代 ×1、2 代 ×3、3 代 ×6 —— 损耗不断加剧
        int lost = generation * (generation + 1) / 2 * penalty;
        return Math.max(FocalDecayConfig.PROTOTYPE_RADIUS.get(), base - lost);
    }

    /**
     * 阶段感知的保护形态（里程碑 7，2026-08-21，PROXYAI §6.5）：
     *  - 生物稳定 / 完全稳定：硬保护（范围内全部，能量耗尽时生物稳定失效）；
     *  - 语义锁定：阶段1/2 硬保护；阶段3 转为软保护——每周期以
     *    {@code stabilityStrength × semantic_lock_stage3_strength} 概率"守住"，
     *    失守才参与突变骰（默认 0.5 = 效果减半）。
     * 硬保护优先于软保护返回。
     */
    public MutationHelper.Protection protectionInfo(BlockPos pos, BlockState state, int stage) {
        MutationHelper.Protection result = MutationHelper.Protection.NONE;
        for (PrototypeEffect effect : prototypeEffects) {
            if (!withinRadius(pos, effect)) {
                continue;
            }
            String type = effect.data().type();
            if (ObserverModelData.TYPE_TOTAL.equals(type)) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_BIO.equals(type) && effect.data().bioEnergy() > 0) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_CANDIDATE.equals(type)
                    && effect.data().progress() >= ObserverModelItem.requiredCandidatePoints(effect.data())) {
                return MutationHelper.Protection.HARD; // 已完成候选 = 完全稳定
            }
            if (ObserverModelData.TYPE_SEMANTIC_LOCK.equals(type)) {
                if (!effect.trained().contains(state.getBlock())) {
                    continue;
                }
                if (stage >= 3) {
                    double strength = FocalDecayConfig.SEMANTIC_LOCK_STAGE3_STRENGTH.get()
                            * effect.data().stabilityStrength();
                    strength = Math.max(0.0, Math.min(1.0, strength));
                    if (strength > result.softChance()) {
                        result = new MutationHelper.Protection(false, strength);
                    }
                } else {
                    return MutationHelper.Protection.HARD;
                }
            }
        }
        return result;
    }

    /** 硬保护判定（渲染/扫描早期跳过用；阶段3语义锁定不再是硬保护）。 */
    public boolean isProtected(BlockPos pos, BlockState state, int stage) {
        return protectionInfo(pos, state, stage).hard();
    }

    /**
     * 计算位置处的引导偏向（方案 A，2026-08-21，PROXYAI §4.2）：
     * 遍历引导模型效果，取"源方块是概念成员且 q 最大"者生效；
     * 无引导则返回 {@link GuidedBias#NONE}。
     * <p>
     * 概念邻域与成员判定都取自效果里登记期算好的 {@link ClassifiedPool}，
     * 逐方块只做一次 {@code boolean[]} 查表和一次半径比较。
     */
    public GuidedBias getGuidedBias(BlockPos pos, BlockState original, int stage) {
        GuidedBias best = null;
        double bestQ = 0.0;
        for (PrototypeEffect effect : prototypeEffects) {
            ClassifiedPool concept = effect.concept();
            if (concept == null || !withinRadius(pos, effect)) {
                continue;
            }
            if (!concept.contains(original.getBlock())) {
                continue;
            }
            double q = GuidedConcept.effectiveQ(effect.data().stabilityStrength(), stage);
            if (q <= bestQ) {
                continue;
            }
            bestQ = q;
            best = new GuidedBias(concept, q);
        }
        return best == null ? GuidedBias.NONE : best;
    }

    private static boolean withinRadius(BlockPos pos, PrototypeEffect effect) {
        return Math.max(Math.abs(pos.getX() - effect.center().getX()),
                Math.max(Math.abs(pos.getY() - effect.center().getY()),
                        Math.abs(pos.getZ() - effect.center().getZ()))) <= effect.radius();
    }

    // ---- 方块诞生周期 ----
    public long getBlockBirthPeriod(BlockPos pos) {
        return blockBirthPeriods.getOrDefault(pos, -1L);
    }

    public void setBlockBirthPeriod(BlockPos pos, long period) {
        blockBirthPeriods.put(pos.immutable(), period);
        setDirty();
    }

    public boolean removeBlockBirthPeriod(BlockPos pos) {
        if (blockBirthPeriods.remove(pos) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public Map<BlockPos, Long> getBlockBirthPeriods() {
        return blockBirthPeriods;
    }

    /**
     * 剪枝（2026-09-15）：诞生周期只影响"从诞生周期 + 1 到当前周期"的回扫，
     * 而回扫本身有 {@link MutationHelper#CUMULATIVE_SCAN_CAP} 的上限，
     * 因此比"当前周期 − 上限"更早的记录<b>在语义上完全等价于不存在</b>。
     * 删掉它们既不改变任何结果，又让这张随建造无上限增长的持久化表重新有界。
     *
     * @return 实际删除的条目数
     */
    public int pruneBirthPeriods(long currentPeriod) {
        long horizon = currentPeriod - MutationHelper.CUMULATIVE_SCAN_CAP;
        if (horizon <= 0 || blockBirthPeriods.isEmpty()) {
            return 0;
        }
        int before = blockBirthPeriods.size();
        blockBirthPeriods.values().removeIf(period -> period < horizon);
        int removed = before - blockBirthPeriods.size();
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }
}
