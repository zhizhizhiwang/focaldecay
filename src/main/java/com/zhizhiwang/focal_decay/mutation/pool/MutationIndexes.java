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

    private MutationIndexes() {
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

    /** 标签或配置变化后调用：丢弃全部缓存，下次访问重建。 */
    public static void invalidate() {
        CACHE.clear();
    }
}
