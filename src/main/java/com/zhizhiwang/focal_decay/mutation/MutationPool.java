package com.zhizhiwang.focal_decay.mutation;

import com.google.common.collect.ImmutableList;
import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 突变池：按 BuiltInRegistries.BLOCK 的 id 升序排列的不可变方块列表（设计大纲 §4.1.1）。
 * 服务端维护版本号，新增方块时递增并在周期边界同步到客户端。
 */
public final class MutationPool {
    private final List<Block> blocks;
    private final long version;

    private MutationPool(List<Block> blocks, long version) {
        this.blocks = ImmutableList.copyOf(blocks);
        this.version = version;
    }

    /** 从集合构建，按注册表 id 升序排序。 */
    public static MutationPool of(Iterable<Block> source, long version) {
        List<Block> sorted = new ArrayList<>();
        source.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(BuiltInRegistries.BLOCK::getId));
        return new MutationPool(sorted, version);
    }

    /** 空池。 */
    public static MutationPool empty(long version) {
        return new MutationPool(List.of(), version);
    }

    public Block get(int index) {
        return blocks.get(index);
    }

    public int size() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public List<Block> snapshot() {
        return blocks;
    }

    public long version() {
        return version;
    }
}
