package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.mutation.GuidedBias;
import com.zhizhiwang.focal_decay.mutation.GuidedConcept;
import com.zhizhiwang.focal_decay.mutation.MutationHelper;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import com.zhizhiwang.focal_decay.network.SyncRegionDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的保护区域镜像：把服务端同步下来的"哪些位置受保护"变成可 O(1) 查询的形式。
 * <p>
 * <b>为什么单独一个类</b>：这是一整条独立的链路——协议（{@code SyncRegionDataPacket} /
 * {@code SyncPrototypePacket} / {@code SyncBirthPeriodPacket}）→ 按维度归档 → 保护判定/
 * 引导模型查询。它与 {@link ClientRenderCache} 的幽灵缓存只有两处接触
 * （{@code protectionInfo} 与 {@code blockBirthPeriod}），却是"客户端算的目标和服务端一致"
 * 的另一半输入（前一半是 {@link MutationSettings}）。分开之后，缓存那边只剩下
 * "怎么算、怎么存、什么时候清"，两边各自可读、可测。
 * <p>
 * <b>为什么查询方法都带 {@code stage} / {@code settings} 参数</b>：本类刻意不持有它们。
 * 那两个量是"当前周期"的函数、由缓存的生命周期管理（快照未到、断线、换世界都要清），
 * 让本类只做一个纯数据镜像，就不必跟着实现一遍同样的清理逻辑。
 * <p>
 * thread-safety：写入全部发生在主线程（payload handler 的 {@code enqueueWork}），
 * 读取发生在主线程与区块编译线程。所以用 {@link ConcurrentHashMap} 存维度，
 * 每份 {@link RegionData} 是不可变快照（整体替换而非原地改）。
 */
final class ClientRegionData {

    /** 某个维度收到的区域数据（原型机列表 + 方块诞生周期）。 */
    private static final class RegionData {
        final List<ClientPrototype> prototypes;
        final Map<BlockPos, Long> birthPeriods;

        RegionData(List<ClientPrototype> prototypes, Map<BlockPos, Long> birthPeriods) {
            this.prototypes = List.copyOf(prototypes);
            this.birthPeriods = Map.copyOf(birthPeriods);
        }
    }

    /** 客户端侧原型机效果镜像。 */
    private record ClientPrototype(BlockPos center, int radius, String type,
                                   Set<Block> trainedBlocks, Set<String> trainedEntities,
                                   boolean bioActive, String concept, int progress, double q, int copies) {
    }

    /**
     * 引导模型的渲染期形态：概念邻域池 + 半径 + 强度。
     * 每次扫描一个区块节时从 {@link RegionData} 解析一次，循环体内只做半径比较和
     * 一次 {@code boolean[]} 成员判定——旧实现是逐方块解析标签 ID 再线性扫标签成员。
     */
    record GuidedModel(BlockPos center, int radius, ClassifiedPool pool, double strength) {
    }

    /** 各维度的数据（由整表快照与单条增量共同维护）。 */
    private final Map<ResourceKey<Level>, RegionData> byDimension = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 写入（主线程：payload handler）
    // ------------------------------------------------------------------

    /**
     * 整表快照（登录/换维度）。
     *
     * @param incoming 新的诞生周期表；调用方负责把包里的两个平行数组解析成 Map
     * @return <b>诞生状态发生变化的位置</b>：新增的、周期变了的、以及消失的。
     *         调用方要用它把那些位置上的幽灵与负缓存一并作废——诞生周期通过
     *         {@code MutationHelper} 的 {@code fromPeriod} 门控直接参与解析，
     *         旧结论在新表下可能是错的。旧表不存在（首次同步/换维度）时返回全部位置。
     */
    Set<BlockPos> applySnapshot(ResourceKey<Level> dimension, List<SyncRegionDataPacket.PrototypeData> prototypes,
                               Map<BlockPos, Long> incoming) {
        List<ClientPrototype> prototypeList = new ArrayList<>(prototypes.size());
        for (SyncRegionDataPacket.PrototypeData p : prototypes) {
            prototypeList.add(toClientPrototype(p));
        }

        RegionData old = byDimension.get(dimension);
        Set<BlockPos> changed = new HashSet<>();
        if (old == null) {
            changed.addAll(incoming.keySet());
        } else {
            for (Map.Entry<BlockPos, Long> entry : incoming.entrySet()) {
                Long previous = old.birthPeriods.get(entry.getKey());
                if (previous == null || !previous.equals(entry.getValue())) {
                    changed.add(entry.getKey());
                }
            }
            for (BlockPos pos : old.birthPeriods.keySet()) {
                if (!incoming.containsKey(pos)) {
                    changed.add(pos);
                }
            }
        }

        byDimension.put(dimension, new RegionData(prototypeList, incoming));
        return changed;
    }

    /**
     * 单条原型机效果的增删改。
     * <p>
     * <b>为什么必须有这条通道</b>：有效原型机列表不落盘，靠方块实体的 {@code onLoad} 重建，
     * 所以登录时发出的整表<b>只包含当时已加载区块里的原型机</b>。玩家走到远处某个原型机旁边时，
     * 服务端开始保护那片区域，客户端却一直以为没人保护、继续画幽灵——挖下去得到的自然是原方块的掉落。
     */
    void applyPrototype(ResourceKey<Level> dimension, long packedPos, boolean present,
                        SyncRegionDataPacket.PrototypeData data) {
        // 整表还没到就先建一份空的：增量与整表的到达顺序不保证（区块加载发生在登录流程中），
        // 丢掉一条增量就等于"客户端永远少知道一个保护范围"。整表到达时整体替换，不会残留。
        RegionData old = byDimension.computeIfAbsent(dimension, key -> new RegionData(List.of(), Map.of()));
        BlockPos pos = BlockPos.of(packedPos);
        List<ClientPrototype> prototypes = new ArrayList<>(old.prototypes.size() + 1);
        for (ClientPrototype prototype : old.prototypes) {
            if (!prototype.center().equals(pos)) {
                prototypes.add(prototype);
            }
        }
        if (present && data != null) {
            prototypes.add(toClientPrototype(data));
        }
        byDimension.put(dimension, new RegionData(prototypes, old.birthPeriods));
    }

    /**
     * 单条诞生周期变化。{@code period < 0} 表示删除。
     * <p>增量可能比整表先到（另一位玩家在你登录的同一刻放了方块），
     * 丢掉它会让客户端把一个"刚被放下的方块"当成世界原生方块，从而显示一个服务端不会执行的目标。
     */
    void applyBirthPeriod(ResourceKey<Level> dimension, BlockPos pos, long period) {
        RegionData old = byDimension.computeIfAbsent(dimension, key -> new RegionData(List.of(), Map.of()));
        Map<BlockPos, Long> births = new HashMap<>(old.birthPeriods);
        if (period < 0) {
            births.remove(pos);
        } else {
            births.put(pos, period);
        }
        byDimension.put(dimension, new RegionData(old.prototypes, births));
    }

    /** 换世界/断线时清空（不同存档的维度键可能相同）。 */
    void clear() {
        byDimension.clear();
    }

    private static ClientPrototype toClientPrototype(SyncRegionDataPacket.PrototypeData p) {
        return new ClientPrototype(BlockPos.of(p.pos()), p.radius(), p.type(),
                parseBlocks(p.trainedTargets()), Set.copyOf(p.trainedEntities()), p.bioActive(),
                p.concept(), p.progress(), p.q(), p.copies());
    }

    /** 把同步来的方块 ID 列表解析成方块集合（保护判定要在热路径上做 O(1) 命中）。 */
    private static Set<Block> parseBlocks(List<String> ids) {
        Set<Block> blocks = new HashSet<>(ids.size());
        for (String id : ids) {
            try {
                blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)));
            } catch (Exception ignored) {
                // 非法 ID 忽略（与服务端解析一致）
            }
        }
        return blocks;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 当前客户端所在维度的保护数据；未同步或无保护返回 null。 */
    private RegionData current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return byDimension.get(mc.level.dimension());
    }

    /**
     * 阶段感知的保护形态（与服务端 {@code MutationPoolManager#protectionInfo} 同一逻辑）：
     * 阶段3语义锁定转为软保护（每周期按强度掷"守住"骰子），生物稳定/完全稳定仍硬保护。
     * <p>
     * 用的两个配置量（阶段3锁定强度、候选体训练点数）取自服务端快照：它们直接决定"有没有保护"，
     * 两端取值不同就会出现"客户端以为有保护、服务端照样转换"。
     */
    MutationHelper.Protection protectionInfo(BlockPos pos, BlockState state, int stage, MutationSettings settings) {
        RegionData data = current();
        if (data == null || settings == null) {
            return MutationHelper.Protection.NONE;
        }
        MutationHelper.Protection result = MutationHelper.Protection.NONE;
        for (ClientPrototype prototype : data.prototypes) {
            if (!withinRadius(pos, prototype.center(), prototype.radius())) {
                continue;
            }
            if (ObserverModelData.TYPE_TOTAL.equals(prototype.type())) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_BIO.equals(prototype.type()) && prototype.bioActive()) {
                return MutationHelper.Protection.HARD;
            }
            if (ObserverModelData.TYPE_CANDIDATE.equals(prototype.type())
                    && prototype.progress() >= settings.requiredCandidatePoints(prototype.copies())) {
                return MutationHelper.Protection.HARD; // 已完成候选 = 完全稳定
            }
            if (ObserverModelData.TYPE_SEMANTIC_LOCK.equals(prototype.type())) {
                if (!prototype.trainedBlocks().contains(state.getBlock())) {
                    continue;
                }
                if (stage >= 3) {
                    double strength = settings.semanticLockStage3() * prototype.q();
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
    boolean isProtected(BlockPos pos, BlockState state, int stage, MutationSettings settings) {
        return protectionInfo(pos, state, stage, settings).hard();
    }

    /** 该方块的诞生周期；未同步或世界原生返回 -1。 */
    long blockBirthPeriod(BlockPos pos) {
        RegionData data = current();
        return data == null ? -1L : data.birthPeriods.getOrDefault(pos, -1L);
    }

    /**
     * 把已同步的引导模型解析成"半径 + 概念池 + 强度"的可直接查询形态，并算出该位置的偏向。
     * 每次扫描一个区块节解析一次：{@code MutationIndexes#tagged} 有缓存，
     * 但也没必要在每个方块上重复走一遍。
     * <p>
     * <b>只读当前维度</b>：{@link #current()} 与 {@link MutationIndexes#get} 用的是同一个维度键。
     */
    List<GuidedModel> guidedModels() {
        RegionData data = current();
        Minecraft mc = Minecraft.getInstance();
        if (data == null || mc.level == null || data.prototypes.isEmpty()) {
            return List.of();
        }
        MutationIndex index = MutationIndexes.get(mc.level.dimension());
        List<GuidedModel> models = new ArrayList<>();
        for (ClientPrototype prototype : data.prototypes) {
            if (!ObserverModelData.TYPE_GUIDED.equals(prototype.type()) || prototype.concept().isEmpty()) {
                continue;
            }
            ClassifiedPool pool = index.tagged(prototype.concept());
            if (pool.isEmpty()) {
                continue;
            }
            models.add(new GuidedModel(prototype.center(), prototype.radius(), pool, prototype.q()));
        }
        return models;
    }

    /**
     * 客户端引导偏向（与服务端 {@code MutationPoolManager#getGuidedBias} 同一公式）：
     * 取"源方块是概念成员且 q 最大"的引导模型生效，否则不引导。
     * 概念邻域与成员判定都来自预解析的池，循环体里只有半径比较和一次 {@code boolean[]} 读。
     * <p>
     * q 相等时按<b>中心坐标</b>决胜而不是按列表顺序：增量同步之后两端的列表顺序不保证一致
     * （服务端是登记顺序，客户端是"快照 + 增量到达顺序"），而 q 完全相等在同类模型上很常见
     * （两个同概念的引导模型）。只比 q 的话，同一格在两台机器上会抽到不同的概念池。
     */
    static GuidedBias guidedBias(List<GuidedModel> models, BlockPos pos, BlockState original,
                                 int stage, boolean halveStage3) {
        GuidedModel best = null;
        double bestQ = 0.0;
        for (GuidedModel model : models) {
            if (!withinRadius(pos, model.center(), model.radius())
                    || !model.pool().contains(original.getBlock())) {
                continue;
            }
            double q = GuidedConcept.effectiveQ(model.strength(), stage, halveStage3);
            if (!GuidedConcept.betterGuided(q, model.center(), bestQ, best == null ? null : best.center())) {
                continue;
            }
            bestQ = q;
            best = model;
        }
        return best == null ? GuidedBias.NONE : new GuidedBias(best.pool(), bestQ);
    }

    /** 切比雪夫距离（原型机保护范围是立方体，不是球体）。 */
    static boolean withinRadius(BlockPos pos, BlockPos center, int radius) {
        return Math.max(Math.abs(pos.getX() - center.getX()),
                Math.max(Math.abs(pos.getY() - center.getY()),
                        Math.abs(pos.getZ() - center.getZ()))) <= radius;
    }
}
