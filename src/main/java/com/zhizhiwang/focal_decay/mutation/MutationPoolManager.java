package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.tags.TagKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 维度级突变池管理器（设计大纲 §4.2）。
 * 存储：区域覆盖列表、稳定锚位置集合、全局池缓存。
 * 提供 getEffectivePool(pos, original) 计算有效池。
 */
public class MutationPoolManager extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_mutation_pool";
    private static final String TAG_OVERRIDES = "Overrides";
    private static final String TAG_ANCHORS = "Anchors";
    private static final String TAG_POOL_VERSION = "PoolVersion";

    private final List<RegionOverride> overrides = new ArrayList<>();
    private final Set<BlockPos> anchorPositions = new HashSet<>();
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
        manager.overrides.clear();
        ListTag overridesTag = tag.getList(TAG_OVERRIDES, Tag.TAG_COMPOUND);
        for (int i = 0; i < overridesTag.size(); i++) {
            manager.overrides.add(RegionOverride.load(overridesTag.getCompound(i), registries));
        }
        manager.anchorPositions.clear();
        long[] anchors = tag.getLongArray(TAG_ANCHORS);
        for (long l : anchors) {
            manager.anchorPositions.add(BlockPos.of(l));
        }
        manager.globalPool = MutationPool.empty(tag.getLong(TAG_POOL_VERSION));
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag overridesTag = new ListTag();
        for (RegionOverride override : overrides) {
            overridesTag.add(override.save(registries));
        }
        tag.put(TAG_OVERRIDES, overridesTag);

        long[] anchors = new long[anchorPositions.size()];
        int i = 0;
        for (BlockPos pos : anchorPositions) {
            anchors[i++] = pos.asLong();
        }
        tag.put(TAG_ANCHORS, new LongArrayTag(anchors));
        tag.putLong(TAG_POOL_VERSION, globalPool.version());
        return tag;
    }

    // ---- 覆盖 ----
    public List<RegionOverride> getOverrides() {
        return overrides;
    }

    public void addOverride(RegionOverride override) {
        overrides.add(override);
        setDirty();
    }

    public void removeOverrideAt(BlockPos controllerPos) {
        overrides.removeIf(o -> o.center().equals(controllerPos));
        setDirty();
    }

    // ---- 锚 ----
    public Set<BlockPos> getAnchorPositions() {
        return anchorPositions;
    }

    public void addAnchor(BlockPos pos) {
        anchorPositions.add(pos.immutable());
        setDirty();
    }

    public void removeAnchor(BlockPos pos) {
        anchorPositions.remove(pos);
        setDirty();
    }

    public boolean isProtected(BlockPos pos) {
        return anchorPositions.stream().anyMatch(anchor -> isWithinRadius(pos, anchor));
    }

    private static boolean isWithinRadius(BlockPos pos, BlockPos anchor) {
        return Math.max(Math.abs(pos.getX() - anchor.getX()),
                Math.max(Math.abs(pos.getY() - anchor.getY()), Math.abs(pos.getZ() - anchor.getZ()))) <= 8;
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

    /**
     * 计算位置处的有效池（设计大纲 §4.2）。
     * 被稳定锚保护 → 空池（不转换）。
     * 命中的覆盖取交集；交集为空回退全局池。
     */
    public List<Block> getEffectivePool(BlockPos pos, BlockState original) {
        if (isProtected(pos)) {
            return List.of();
        }
        Set<Block> result = null;
        for (RegionOverride override : overrides) {
            if (!override.contains(pos)) {
                continue;
            }
            List<Block> subPool = resolveSubPool(override, original);
            if (subPool.isEmpty()) {
                continue;
            }
            Set<Block> sub = new HashSet<>(subPool);
            result = result == null ? sub : intersection(result, sub);
            if (result.isEmpty()) {
                break;
            }
        }
        if (result == null) {
            return globalPool.snapshot();
        }
        return result.isEmpty() ? globalPool.snapshot() : new ArrayList<>(result);
    }

    /** 解析覆盖子池：标签匹配的方块，剔除带方块实体的。 */
    private List<Block> resolveSubPool(RegionOverride override, BlockState original) {
        Set<TagKey<Block>> tags = override.tags();
        if (tags.isEmpty()) {
            return List.of();
        }
        List<Block> subPool = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            boolean matches = false;
            for (TagKey<Block> tagKey : tags) {
                if (state.is(tagKey)) {
                    matches = true;
                    break;
                }
            }
            if (matches && !state.hasBlockEntity()) {
                subPool.add(block);
            }
        }
        return subPool;
    }

    private static Set<Block> intersection(Set<Block> a, Set<Block> b) {
        Set<Block> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }
}
