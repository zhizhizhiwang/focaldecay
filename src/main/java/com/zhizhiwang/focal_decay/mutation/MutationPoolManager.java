package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 维度级突变池管理器（设计大纲 §4.2 / §4.3）。
 * 存储：有效原型机效果（位置 + 半径 + 模型数据）、方块诞生周期、全局池缓存。
 * 提供 getGuidedBias(pos, state, stage, registries) 计算引导偏向；isProtected 由原型机模型效果决定。
 */
public class MutationPoolManager extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_mutation_pool";
    private static final String TAG_POOL_VERSION = "PoolVersion";
    private static final String TAG_BIRTHS = "Births";

    /** 有效原型机效果：中心、切比雪夫半径、模型训练数据（瞬态，由方块实体在加载/换模时重建）。 */
    public record PrototypeEffect(BlockPos center, int radius, ObserverModelData data) {
    }

    private final List<PrototypeEffect> prototypeEffects = new ArrayList<>();
    /** 玩家放置方块的"诞生周期"（位置 -> 放置时的 periodIndex）。 */
    private final Map<BlockPos, Long> blockBirthPeriods = new HashMap<>();
    private MutationPool globalPool = MutationPool.empty(0);

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
        manager.globalPool = MutationPool.empty(tag.getLong(TAG_POOL_VERSION));
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
        tag.putLong(TAG_POOL_VERSION, globalPool.version());
        return tag;
    }

    // ---- 原型机效果 ----
    public List<PrototypeEffect> getPrototypeEffects() {
        return prototypeEffects;
    }

    /**
     * 原型机模型变化时更新效果：有有效模型（非空白）则加入/更新，否则移除。
     * 模型半径：语义锁定/引导 = 基础半径，生物稳定 +4，完全稳定固定 32。
     */
    public void updatePrototypeEffect(BlockPos pos, ItemStack modelStack) {
        prototypeEffects.removeIf(e -> e.center().equals(pos));
        ObserverModelData data = modelStack.getItem() instanceof ObserverModelItem
                ? ObserverModelItem.getData(modelStack) : null;
        if (data != null && !ObserverModelData.TYPE_BLANK.equals(data.type())) {
            prototypeEffects.add(new PrototypeEffect(pos.immutable(), radiusFor(data), data));
        }
    }

    public void removePrototypeEffect(BlockPos pos) {
        prototypeEffects.removeIf(e -> e.center().equals(pos));
    }

    /** 模型效果半径（供放置固化与效果注册共用）：生物稳定 +4，完全稳定固定 32。 */
    public static int radiusFor(ObserverModelData data) {
        return switch (data.type()) {
            case ObserverModelData.TYPE_BIO -> FocalDecayConfig.PROTOTYPE_RADIUS.get() + 4;
            case ObserverModelData.TYPE_TOTAL -> 32;
            default -> FocalDecayConfig.PROTOTYPE_RADIUS.get();
        };
    }

    /**
     * 位置是否被任一原型机效果"保护"（不参与视觉转换、交互不转换）：
     *  - 生物稳定：范围内全部（能量耗尽时不保护）；
     *  - 完全稳定：范围内全部；
     *  - 语义锁定：真实方块 ID 在 trainedTargets 中。
     */
    public boolean isProtected(BlockPos pos, BlockState state) {
        for (PrototypeEffect effect : prototypeEffects) {
            if (!withinRadius(pos, effect)) {
                continue;
            }
            String type = effect.data().type();
            if (ObserverModelData.TYPE_TOTAL.equals(type)) {
                return true;
            }
            if (ObserverModelData.TYPE_BIO.equals(type) && effect.data().bioEnergy() > 0) {
                return true;
            }
            if (ObserverModelData.TYPE_SEMANTIC_LOCK.equals(type)) {
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (effect.data().trainedTargets().contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 计算位置处的引导偏向（方案 A，2026-08-21，PROXYAI §4.2）：
     * 遍历引导模型效果，取"源方块是概念成员且 q 最大"者生效；
     * 无引导则返回 {@link GuidedBias#NONE}，目标完全走全局池。
     */
    public GuidedBias getGuidedBias(BlockPos pos, BlockState original, int stage) {
        GuidedBias best = null;
        for (PrototypeEffect effect : prototypeEffects) {
            if (!withinRadius(pos, effect) || !ObserverModelData.TYPE_GUIDED.equals(effect.data().type())) {
                continue;
            }
            String concept = effect.data().concept();
            if (concept.isEmpty()) {
                continue;
            }
            if (!GuidedConcept.isMember(original.getBlock(), concept)) {
                continue;
            }
            double q = GuidedConcept.effectiveQ(effect.data().stabilityStrength(), stage);
            if (q <= 0.0) {
                continue;
            }
            List<Block> conceptPool = GuidedConcept.neighborhood(concept);
            if (conceptPool.isEmpty()) {
                continue;
            }
            if (best == null || q > best.q()) {
                best = new GuidedBias(conceptPool, q);
            }
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

    // ---- 全局池 ----
    public MutationPool getGlobalPool() {
        return globalPool;
    }

    public void setGlobalPool(MutationPool pool) {
        this.globalPool = pool;
        setDirty();
    }

    /** 从标签加载全局池（服务端启动/维度加载时调用）。 */
    public void reloadGlobalPool(ServerLevel level) {
        List<Block> blocks = new ArrayList<>();
        level.registryAccess().lookupOrThrow(Registries.BLOCK)
                .get(ModTags.Blocks.GLOBAL_MUTATION_POOL)
                .ifPresent(holders -> holders.forEach(holder -> blocks.add(holder.value())));
        this.globalPool = MutationPool.of(blocks, globalPool.version() + 1);
        setDirty();
    }
}
