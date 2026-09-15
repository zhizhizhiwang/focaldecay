package com.zhizhiwang.focal_decay.mutation.pool;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 一个方块标签按<b>形态类</b>切分后的确定性候选列表（2026-09-15）。
 * <p>
 * 这是突变抽取真正使用的最小单元：{@code pool.get(shapeClass, index)}。
 * 切分是必要的——同一个语义池里既有完整方块也有楼梯、半砖，而形态类门控要求
 * "目标必须与源同形态"，若在抽取时才发现候选被过滤掉，索引就会空洞化并破坏确定性；
 * 提前切开之后，抽取退化成"一次数组索引"，没有任何条件判断。
 * <p>
 * <b>确定性契约</b>：每个形态类下的成员一律<b>按 {@code BuiltInRegistries.BLOCK.getId} 升序</b>。
 * 方块是静态注册表，注册顺序由模组加载顺序和原版固定，服务端与客户端一致，
 * 因此 {@code index} 在两端指向同一个方块——这是整套预览/服务端一致性的地基。
 */
public final class ClassifiedPool {

    private final String tagId;
    /** 下标 = 形态类编号，值 = 该形态类下按注册表 id 升序的成员；不参与的类为空数组。 */
    private final Block[][] byClass;
    /** 按方块注册表 id 索引的成员标记，用于 O(1) 的"源方块是否属于本池"判定。 */
    private final boolean[] membership;
    /** 跨形态类的扁平视图：给"没有形态约束"的抽取用（例如掉落物被突变成随机方块物品）。 */
    private final Block[] flat;
    private final int total;

    private ClassifiedPool(String tagId, Block[][] byClass, boolean[] membership, int total) {
        this.tagId = tagId;
        this.byClass = byClass;
        this.membership = membership;
        this.total = total;
        this.flat = flatten(byClass);
    }

    private static Block[] flatten(Block[][] byClass) {
        Block[] result = new Block[0];
        for (Block[] bucket : byClass) {
            if (bucket.length == 0) {
                continue;
            }
            Block[] merged = new Block[result.length + bucket.length];
            System.arraycopy(result, 0, merged, 0, result.length);
            System.arraycopy(bucket, 0, merged, result.length, bucket.length);
            result = merged;
        }
        return result;
    }

    /** 空池（标签不存在或全部成员被过滤）。 */
    public static ClassifiedPool empty(String tagId, ShapeClasses shapeClasses) {
        return new ClassifiedPool(tagId, emptyBuckets(shapeClasses.count()), new boolean[0], 0);
    }

    /**
     * 从标签构建。
     *
     * @param tagId       方块标签的完整 ID
     * @param shapeClasses 形态类登记表
     * @param accept      成员过滤（排除免疫方块、方块实体方块、空气等），在<b>两端必须完全一致</b>
     */
    public static ClassifiedPool of(String tagId, ShapeClasses shapeClasses, Predicate<Block> accept) {
        if (tagId.isEmpty()) {
            return empty(tagId, shapeClasses);
        }
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId));
        List<Block> members = new ArrayList<>();
        BuiltInRegistries.BLOCK.getTag(tag).ifPresent(holders -> holders.forEach(holder -> {
            Block block = holder.value();
            if (accept.test(block) && shapeClasses.participates(BuiltInRegistries.BLOCK.getId(block))) {
                members.add(block);
            }
        }));
        return ofMembers(tagId, shapeClasses, members);
    }

    /** 从已有的成员集合构建（构建期已经算好成员时用，省一次标签查找）。 */
    public static ClassifiedPool ofMembers(String tagId, ShapeClasses shapeClasses, List<Block> members) {
        Block[][] buckets = emptyBuckets(shapeClasses.count());
        boolean[] membership = new boolean[BuiltInRegistries.BLOCK.size()];
        List<List<Block>> collected = new ArrayList<>(buckets.length);
        for (int i = 0; i < buckets.length; i++) {
            collected.add(new ArrayList<>());
        }
        for (Block block : members) {
            int id = BuiltInRegistries.BLOCK.getId(block);
            if (id >= 0 && id < membership.length) {
                membership[id] = true;
            }
            int shapeClass = shapeClasses.of(block);
            if (shapeClass != ShapeClasses.NONE) {
                collected.get(shapeClass).add(block);
            }
        }
        int total = 0;
        for (int i = 0; i < buckets.length; i++) {
            List<Block> bucket = collected.get(i);
            if (bucket.isEmpty()) {
                continue;
            }
            // 注册表顺序不保证稳定，必须显式排序，索引才是两端可复现的。
            bucket.sort(Comparator.comparingInt(BuiltInRegistries.BLOCK::getId));
            buckets[i] = bucket.toArray(new Block[0]);
            total += bucket.size();
        }
        return new ClassifiedPool(tagId, buckets, membership, total);
    }

    private static Block[][] emptyBuckets(int classCount) {
        Block[][] buckets = new Block[Math.max(classCount, ShapeClasses.FIRST_CUSTOM)][];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = EMPTY_MEMBERS;
        }
        return buckets;
    }

    private static final Block[] EMPTY_MEMBERS = new Block[0];

    /** 该形态类下的候选数量。 */
    public int count(int shapeClass) {
        return shapeClass >= 0 && shapeClass < byClass.length ? byClass[shapeClass].length : 0;
    }

    /** 该形态类下的第 {@code index} 个候选；调用方保证 {@code index < count(shapeClass)}。 */
    public Block get(int shapeClass, int index) {
        return byClass[shapeClass][index];
    }

    /** 该形态类是否可用（候选数量 ≥ {@code minimum}）。 */
    public boolean usable(int shapeClass, int minimum) {
        return count(shapeClass) >= minimum;
    }

    /** 方块是否是本池成员（O(1)，引导模型的"源门控"用它取代线性扫标签）。 */
    public boolean contains(Block block) {
        int id = BuiltInRegistries.BLOCK.getId(block);
        return id >= 0 && id < membership.length && membership[id];
    }

    /** 全形态类成员总数（诊断用）。 */
    public int total() {
        return total;
    }

    /**
     * 跨形态类的扁平成员数组（顺序 = 形态类编号升序，类内按注册表 id 升序）。
     * 给"没有几何约束"的抽取路径用——掉落物被突变成随机方块物品时不存在形态类可言。
     */
    public Block[] flat() {
        return flat;
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public String tagId() {
        return tagId;
    }
}
