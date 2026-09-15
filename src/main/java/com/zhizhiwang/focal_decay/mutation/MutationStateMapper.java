package com.zhizhiwang.focal_decay.mutation;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/**
 * 方块状态迁移（2026-09-15）：把源方块状态里"目标方块也有"的属性值搬过去。
 * <p>
 * 形态类门控保证了源与目标几何同类，但<b>状态</b>还得自己搬：楼梯的
 * {@code facing/half/shape}、半砖的 {@code type}、原木的 {@code axis}、带釉陶瓦的
 * {@code facing}——不搬的话，一座朝东的橡木楼梯突变后会立起来变成默认朝向，
 * 双半砖会缩回半砖，横放的原木会竖起来。
 * <p>
 * 实现刻意复用了原版自己的两个 API，从而<b>完全不需要强转</b>：
 * {@link Property#getName(Comparable)} 拿到值的名字、{@link Property#getValue(String)}
 * 按目标方块自己的属性把名字解析回同类型的值。名字对不上（目标没这个属性）就直接跳过，
 * 因此任何模组方块都安全。
 * <p>
 * 排除项：
 * <ul>
 *   <li>{@code waterlogged}——目标保持干燥。否则含水的目标状态会被
 *       {@code isRenderableTarget} 的流体检查挡掉，出现"服务端已转换、客户端不显示幽灵"的
 *       两端不一致（水流体并不随方块一起搬走，搬这个属性本身也没有意义）。</li>
 *   <li>{@code in_wall}——栅栏贴墙时的下沉标记。它在真实的方块更新里由
 *       {@code updateShape} 自动修正，而预览路径不会有更新，搬过去只会让幽灵下沉。</li>
 * </ul>
 * <b>缓存</b>：映射是 (源状态, 目标方块) 的纯函数，可以用一个长整型键的表缓存。
 * 有属性的源方块（楼梯/原木等）在扫描里会被反复命中同一批组合，缓存把它们从
 * "逐属性字符串查找"降成一次数组探测。无属性的源方块（绝大多数完整方块）走空属性快速路径，
 * 连缓存都不碰。
 */
public final class MutationStateMapper {

    /** 不迁移的属性名。 */
    private static final Set<String> EXCLUDED = Set.of("waterlogged", "in_wall");

    /**
     * 每个线程的缓存条目上限；超出直接清空重建，避免无限增长。
     * <p>
     * 这是<b>每线程</b>的上限，所以别开太大：客户端 32 核时同时干活的编译线程可能有一二十个。
     * 实际上单个线程见到的不同 (源状态, 目标方块) 组合通常只有几百到几千个。
     */
    private static final int CACHE_LIMIT = 1 << 12;

    private static final MutationStateMapper INSTANCE = new MutationStateMapper();

    /**
     * 缓存必须<b>每线程一份</b>（2026-09-16 修复崩溃）。
     * <p>
     * 原先这里是一张全局共享的 {@code Long2ObjectOpenHashMap}，结果线上崩了：
     * <pre>
     *   ArrayIndexOutOfBoundsException: Index 8192 out of bounds for length 4097
     *     at Long2ObjectOpenHashMap.rehash -> put -> MutationStateMapper.map
     *     at SectionRenderDispatcher$RenderSection$RebuildTask.doTask   // ForkJoinPool worker
     * </pre>
     * <b>区块编译是并发跑在 ForkJoinPool 上的</b>：多个 worker 同时编译不同区块节，
     * 也就同时往这张表里 put，扩容时把内部数组写坏。（{@code ClientRenderCache} 里到处用
     * {@code ConcurrentHashMap} 就是因为知道这条路是多线程的，这里当时漏了。）
     * <p>
     * 为什么用 ThreadLocal 而不是 ConcurrentHashMap：一是无锁无竞争；二是键是 {@code long}，
     * ConcurrentHashMap 每次 get/put 都要装箱成 {@code Long}，而这里正是逐方块的编译热路径，
     * 又要把刚消掉的分配加回来。映射是纯函数，所以线程各存一份不会有任何一致性代价。
     */
    private final ThreadLocal<Long2ObjectOpenHashMap<BlockState>> cache =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

    private MutationStateMapper() {
    }

    /** 全局实例（映射无状态，缓存按线程隔离）。 */
    public static MutationStateMapper get() {
        return INSTANCE;
    }

    /** 目标方块在源状态下"最等价"的方块状态。 */
    public BlockState map(BlockState source, Block target) {
        BlockState base = target.defaultBlockState();
        if (source.getProperties().isEmpty()) {
            return base; // 快速路径：没有任何可迁移的属性，连缓存都不用碰
        }
        Long2ObjectOpenHashMap<BlockState> local = cache.get();
        long key = key(source, target);
        BlockState cached = local.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState mapped = transfer(source, base);
        if (local.size() >= CACHE_LIMIT) {
            local.clear();
        }
        local.put(key, mapped);
        return mapped;
    }

    private static long key(BlockState source, Block target) {
        return ((long) Block.getId(source) << 32) | (BuiltInRegistries.BLOCK.getId(target) & 0xFFFFFFFFL);
    }

    private static BlockState transfer(BlockState source, BlockState target) {
        BlockState result = target;
        for (Property<?> sourceProperty : source.getProperties()) {
            String name = sourceProperty.getName();
            if (EXCLUDED.contains(name)) {
                continue;
            }
            Property<?> targetProperty = target.getBlock().getStateDefinition().getProperty(name);
            if (targetProperty == null) {
                continue;
            }
            result = copy(result, source, sourceProperty, targetProperty);
        }
        return result;
    }

    /** 通配捕获：把 {@code Property<?>} 交给泛型方法时由编译器推断出实际类型，无需强转。 */
    private static <T extends Comparable<T>> BlockState copy(BlockState result, BlockState source,
                                                             Property<T> sourceProperty, Property<?> targetProperty) {
        return apply(result, targetProperty, sourceProperty.getName(source.getValue(sourceProperty)));
    }

    private static BlockState apply(BlockState result, Property<?> targetProperty, String valueName) {
        return applyTyped(result, targetProperty, valueName);
    }

    private static <T extends Comparable<T>> BlockState applyTyped(BlockState result, Property<T> targetProperty,
                                                                   String valueName) {
        // 名字对得上但取值域不同（例如模组方块自定义了同名 facing）时 getValue 返回空，自动跳过。
        return targetProperty.getValue(valueName).map(value -> result.setValue(targetProperty, value)).orElse(result);
    }
}
