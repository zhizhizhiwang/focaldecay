package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MutationIndex} 的生命周期管理（2026-09-15）：按维度惰性构建、缓存、在标签更新时整体丢弃。
 * <p>
 * 索引只依赖"方块标签 + 形态类 + 配置"，与 RegistryAccess 实例无关，所以缓存键就是维度
 * （唯一随维度变化的是大池标签）。数据包重载 / 客户端收到标签同步时清空，
 * 下一次访问自动重建——客户端标签尚未到位时也不会崩，只是暂时抽不出目标，同步完即自愈。
 * <p>
 * 构建要遍历全部方块与标签（毫秒级），刻意做成惰性：服务端只在真正需要时才付这笔钱，
 * 客户端则是在标签同步之后第一次扫描时构建。
 */
public final class MutationIndexes {

    private static final Map<ResourceKey<Level>, MutationIndex> CACHE = new ConcurrentHashMap<>();

    /**
     * {@code wild_auto_include} 的当前取值，由两端各自的权威来源设置：
     * 服务端从配置（{@code MutationSettings.server}），客户端从同步下来的快照。
     * <p>
     * <b>为什么要绕这一道</b>（2026-09-30，BACKLOG `P0-7`）：这个开关决定<b>大池的成员</b>，
     * 因而决定每一个抽到的目标；而 {@link MutationIndexBuilder} 是两端的<b>同一段代码</b>。
     * 以前它在构建期直接读本端 {@code FocalDecayConfig}，于是服务端与客户端各按自己那份 toml 建池——
     * 单人环境天然相同所以从不暴露，局域网里改过配置的客户端就会算出另一个世界。
     * 现在取值来源与其它输入统一：服务端的快照 == 客户端收到的快照。
     */
    private static volatile boolean wildAutoInclude = true;

    private MutationIndexes() {
    }

    /**
     * 设置 {@code wild_auto_include}。取值变化时<b>必须丢弃索引缓存</b>——
     * 它决定池成员，旧索引在新的取值下是错的。
     */
    public static void setWildAutoInclude(boolean value) {
        if (wildAutoInclude != value) {
            wildAutoInclude = value;
            invalidate();
        }
    }

    static boolean wildAutoInclude() {
        return wildAutoInclude;
    }

    /** 取某维度的索引；不存在则构建。 */
    public static MutationIndex get(ResourceKey<Level> dimension) {
        return CACHE.computeIfAbsent(dimension, MutationIndexes::build);
    }

    private static MutationIndex build(ResourceKey<Level> dimension) {
        try {
            return new MutationIndexBuilder(dimension).build();
        } catch (RuntimeException | LinkageError e) {
            // 极端情况（数据包标签畸形）不应该让整个渲染循环崩掉：退化为"什么都不突变"。
            FocalDecay.LOGGER.error("[focal_decay] failed to build mutation index for {}, mutations disabled",
                    dimension.location(), e);
            return MutationIndex.empty();
        }
    }

    /**
     * 标签或配置变化后调用：丢弃全部缓存，下次访问重建。
     * <p>
     * <b>丢弃前先把每个索引内部的派生缓存释放掉</b>（BACKLOG `P1-6` 第 11 条）：
     * {@code tagPools} 一张表可能装着几百份 {@code boolean[registry.size()]}，
     * 而"从 CACHE 里移除"只是让它变成垃圾，真正回收要等 GC；重载是可以在游玩中途反复发生的。
     * <p>
     * 已经持有某个池对象的调用方不受影响（{@code releaseCaches()} 不改池对象本身）——
     * 但"服务端已登记的原型机效果持有旧池"是<b>另一件事</b>，见
     * {@code MutationPoolManager#refreshConceptPools}。
     */
    public static void invalidate() {
        for (MutationIndex index : CACHE.values()) {
            index.releaseCaches();
        }
        CACHE.clear();
    }
}
