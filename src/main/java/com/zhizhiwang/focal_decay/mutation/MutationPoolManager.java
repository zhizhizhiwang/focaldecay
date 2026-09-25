package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
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
    /**
     * {@link #prototypeEffects} 的不可变视图缓存（2026-09-25，BACKLOG `P1-6` 第 5 条）。
     * <p>
     * 旧实现把内部那张<b>可变</b>列表直接交出去（{@code getPrototypeEffects()}），而调用方
     * （实体突变、稳定场粒子、命令…）会长时间持有它。只要有人顺手改一下，
     * 就变成"绕过登记流程修改有效原型机列表"——那正是最难查的一类 bug。
     * 现在对外一律给不可变视图；内部改动后把它置空，下次访问重建。
     * <p>
     * 为什么不是每次 {@code List.copyOf}：{@code convertPrototypeRange} 会在逐坐标循环里
     * 调 {@code protectionInfo}/{@code getGuidedBias}，而那些方法要遍历这份列表——
     * 每次复制一份 27 万次是不可接受的。缓存 + 失效是这里唯一合理的做法。
     */
    private List<PrototypeEffect> prototypeEffectsView;
    /**
     * 上一次<b>发给客户端</b>的效果摘要（位置 -> 摘要）。增量同步的判据：
     * 新摘要与这里存的不相等才发包。存的是"客户端现在以为的样子"，所以
     * "客户端能观察到的字段变了没有"这个问题不需要另写一份签名去维护。
     * <p>
     * 瞬态，不落盘：它描述的是网络同步状态，不是世界状态。
     */
    private final Map<BlockPos, SyncRegionDataPacket.PrototypeData> syncedEffects = new HashMap<>();
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
    /**
     * 有效原型机效果的<b>不可变视图</b>。调用方可以长期持有它——内部改动会让旧视图失效，
     * 但旧视图本身不会被改坏（见 {@link #prototypeEffectsView}）。
     */
    public List<PrototypeEffect> getPrototypeEffects() {
        List<PrototypeEffect> view = prototypeEffectsView;
        if (view == null) {
            view = List.copyOf(prototypeEffects);
            prototypeEffectsView = view;
        }
        return view;
    }

    /**
     * 原型机模型变化时更新效果：有有效模型（非空白）则加入/更新，否则移除。
     * 模型半径：语义锁定/引导 = 基础半径，生物稳定 +4，完全稳定固定 32。
     * 登记期顺带把该模型用得上的查表算好（被训练方块集合、概念邻域池）。
     * <p>
     * <b>顺带做增量同步</b>（2026-09-17）：效果只在"客户端能观察到的字段"真的变了时才发一条包。
     * 以前这里是"变化就重发整张区域表"，而区域表里还挂着随建造无上限增长的诞生周期表；
     * 更要紧的是 {@code onLoad} 那条路径（区块加载时重建效果）根本没发包，
     * 于是客户端在远处原型机的保护范围里会继续画幽灵，挖下去自然对不上。
     * <p>
     * 为什么判据用"与上次发出的摘要比较"而不是"数据变了没有"：生物稳定模型的能量每刻都在变，
     * 但客户端只关心它是否大于 0。摘要里发的正是"是否生效"，所以比较摘要恰好等于比较
     * "客户端看到的东西"，不会退化成每刻一包。
     */
    public void updatePrototypeEffect(ServerLevel level, BlockPos pos, ItemStack modelStack) {
        prototypeEffects.removeIf(e -> e.center().equals(pos));
        prototypeEffectsView = null; // 内部列表变了：丢弃不可变视图缓存
        ObserverModelData data = modelStack.getItem() instanceof ObserverModelItem
                ? ObserverModelItem.getData(modelStack) : null;
        if (data == null || ObserverModelData.TYPE_BLANK.equals(data.type())) {
            syncRemoval(level, pos);
            return;
        }
        MutationIndex index = MutationIndexes.get(level.dimension());
        PrototypeEffect effect = new PrototypeEffect(pos.immutable(), radiusFor(data), data,
                parseTrained(data.trainedTargets()), conceptPool(data, index));
        prototypeEffects.add(effect);
        prototypeEffectsView = null;
        syncAddition(level, effect);
    }

    /** 移除某个位置的原型机效果（方块被破坏/模型被取出）；变化时广播删除增量。 */
    public void removePrototypeEffect(ServerLevel level, BlockPos pos) {
        prototypeEffects.removeIf(e -> e.center().equals(pos));
        prototypeEffectsView = null;
        syncRemoval(level, pos);
    }

    private void syncAddition(ServerLevel level, PrototypeEffect effect) {
        SyncRegionDataPacket.PrototypeData packet = ModNetwork.prototypeData(effect);
        SyncRegionDataPacket.PrototypeData previous = syncedEffects.put(effect.center(), packet);
        if (!packet.equals(previous)) {
            ModNetwork.sendPrototype(level, effect.center(), packet);
        }
    }

    /**
     * 广播删除增量，并顺手清掉"已经不存在的中心"的残留条目。
     * <p>
     * <b>为什么要顺手清</b>（2026-09-25，BACKLOG `P1-6` 第 6 条）：{@code syncedEffects} 原先只在
     * 这个方法里删条目，而被爆炸 / 活塞 / {@code /setblock} 移除的基座<b>不会</b>走这条路径
     * （那些方式不触发 {@code BlockEvent.BreakEvent}）——它们留下的条目会一直挂在表里，
     * 表随"曾经存在过的基座数量"单调增长。
     * <p>
     * 判据是精确的：{@code syncedEffects} 描述的是"客户端现在以为的样子"，
     * 所以一个中心只要不在当前有效效果列表里，它就不该在表里。
     * 放在这个方法里是因为它本来就在"效果集合刚发生变化"时被调用。
     */
    private void syncRemoval(ServerLevel level, BlockPos pos) {
        if (syncedEffects.remove(pos) != null) {
            ModNetwork.sendPrototype(level, pos, null);
        }
        pruneSyncedEffects();
    }

    /** 丢掉 {@code syncedEffects} 里没有对应有效效果的条目（见 {@link #syncRemoval}）。 */
    private void pruneSyncedEffects() {
        if (syncedEffects.isEmpty()) {
            return;
        }
        if (prototypeEffects.isEmpty()) {
            syncedEffects.clear();
            return;
        }
        // 有效中心做成集合再删：两者都只跟"当前有多少座基座"同阶，不在热路径上。
        Set<BlockPos> live = new HashSet<>(prototypeEffects.size() * 2);
        for (PrototypeEffect effect : prototypeEffects) {
            live.add(effect.center());
        }
        syncedEffects.keySet().removeIf(key -> !live.contains(key));
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

    /**
     * 完全稳定模型的半径：32 减去年限递增的复制损耗，并不低于基础半径。
     * <p>
     * 用 {@code long} 算三角数并把结果夹进范围（2026-09-25，BACKLOG `P1-6` 第 8 条）：
     * 旧实现在 int 里算 {@code generation * (generation + 1) / 2 * penalty}，
     * 越界的 {@code copies}（可以来自手改存档/病态数据包）会让它溢出成负数或巨大值。
     * 虽然末尾的 {@code Math.max} 兜住了"半径变成负数"这一种后果，
     * 但<b>不该依赖下游的兜底</b>——半径会被当作固化循环的边界使用，一个巨大的值就是一次长时间卡顿。
     * 这里同时做了两层：算术用 long 不可能溢出，返回值再夹进 [基础半径, 32]。
     */
    private static int totalStabilityRadius(int copies) {
        int base = 32;
        int floor = FocalDecayConfig.PROTOTYPE_RADIUS.get();
        long penalty = Math.max(0, FocalDecayConfig.TOTAL_STABILITY_COPY_PENALTY.get());
        long generation = Math.max(0, copies);
        // 三角数：1 代 ×1、2 代 ×3、3 代 ×6 —— 损耗不断加剧
        long lost = generation * (generation + 1) / 2 * penalty;
        long radius = base - lost;
        if (radius < floor) {
            return floor;
        }
        return (int) Math.min(radius, base);
    }

    /**
     * 阶段感知的保护形态（里程碑 7，2026-08-21，PROXYAI §6.5）：
     *  - 生物稳定 / 完全稳定：硬保护（范围内全部，能量耗尽时生物稳定失效）；
     *  - 语义锁定：阶段1/2 硬保护；阶段3 转为软保护——每周期以
     *    {@code stabilityStrength × semantic_lock_stage3_strength} 概率"守住"，
     *    失守才参与突变骰（默认 0.5 = 效果减半）。
     * 硬保护优先于软保护返回。
     *
     * @param settings 服务端权威输入快照。语义锁定强度取自它而不是本端配置
     *                 （2026-09-30，BACKLOG `P0-7`）：客户端读的只可能是同步下来的快照，
     *                 服务端读配置就会留下两条取值路径，而它们在局域网里会分叉。
     */
    public MutationHelper.Protection protectionInfo(BlockPos pos, BlockState state, int stage,
                                                    MutationSettings settings) {
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
                    double strength = settings.semanticLockStage3() * effect.data().stabilityStrength();
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
    public boolean isProtected(BlockPos pos, BlockState state, int stage, MutationSettings settings) {
        return protectionInfo(pos, state, stage, settings).hard();
    }

    /**
     * 计算位置处的引导偏向（方案 A，2026-08-21，PROXYAI §4.2）：
     * 遍历引导模型效果，取"源方块是概念成员且 q 最大"者生效；
     * 无引导则返回 {@link GuidedBias#NONE}。
     * <p>
     * 概念邻域与成员判定都取自效果里登记期算好的 {@link ClassifiedPool}，
     * 逐方块只做一次 {@code boolean[]} 查表和一次半径比较。并列决胜规则见
     * {@link GuidedConcept#betterGuided}。
     */
    public GuidedBias getGuidedBias(BlockPos pos, BlockState original, int stage) {
        PrototypeEffect best = null;
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
            // 决胜规则与客户端共用（并列按中心坐标）：增量同步之后两端的遍历顺序不保证一致
            if (!GuidedConcept.betterGuided(q, effect.center(), bestQ, best == null ? null : best.center())) {
                continue;
            }
            bestQ = q;
            best = effect;
        }
        return best == null ? GuidedBias.NONE : new GuidedBias(best.concept(), bestQ);
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
