package com.zhizhiwang.focal_decay.mutation.pool;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块突变的<b>预计算查表</b>（2026-09-15）：整套"源门控 / 形态类 / 候选集"的运行时形态。
 * <p>
 * 设计目标只有一个——把热路径压成"几次数组索引"。客户端每 2 帧就要扫一遍 16 区块半径内
 * 所有区块节的所有方块，逐方块做标签查找 / 字符串比较 / 集合运算是不可能接受的，
 * 所以这里把全部标签语义在构建期摊平：
 * <ul>
 *   <li>{@link #isSource} —— 原来是一串 {@code isCollisionShapeFullBlock + hasBlockEntity + 标签判定}；</li>
 *   <li>{@link #shapeClass} —— 一次 {@code int[]} 读，取代构建期的方块类/形状判断；</li>
 *   <li>{@link #localCount}/{@link #local} —— 本方块所属全部语义池的并集，且已经按形态类切好。</li>
 * </ul>
 * <p>
 * <b>对称性与"永不冻结"是结构保证的</b>，不是配置纪律。构建期对每个（池 × 形态类）切片
 * 施加一条规则：<b>切片成员少于 2 个就整体作废</b>。由此可得：
 * <ul>
 *   <li>若 b 能经局部池被抽到，说明存在含 b 且在该形态类下 ≥2 个成员的切片 P，
 *       于是 {@code local(b) ⊇ P}，即 {@code |local(b)| ≥ 2}；</li>
 *   <li>若 b 能经 {@code wild} 大池被抽到，则 {@code |wild ∩ 形态类(b)| ≥ 2}。</li>
 * </ul>
 * 两种情况下 b 自己都至少有 2 个不同的候选，<b>没有任何状态可以被"停住"</b>。
 * 同时"共享至少一个池且同形态类"这个关系对两个方块完全对称，
 * 因此 {@code A→B} 与 {@code B→A} 同时成立或同时不成立。
 * <p>
 * 被抽到的目标方块还自带一条底线：它是某个可用切片的成员，而那个切片里的其它成员
 * 随时能把它换掉——这就是"长时间下不会收敛于固定物品"的形式化表述。
 */
public final class MutationIndex {

    private static final Block[] NO_CANDIDATES = new Block[0];

    private final ShapeClasses shapeClasses;
    private final ClassifiedPool wild;
    private final Block[][] local;
    private final boolean[] source;
    private final Set<Block> immune;
    private final List<String> poolTagIds;
    /** 动态标签池（引导模型的概念标签等）的惰性缓存；只在模型装载/换模时访问，不在扫描热路径上。 */
    private final ConcurrentHashMap<String, ClassifiedPool> tagPools = new ConcurrentHashMap<>();

    MutationIndex(ShapeClasses shapeClasses, ClassifiedPool wild, Block[][] local, boolean[] source,
                  Set<Block> immune, List<String> poolTagIds) {
        this.shapeClasses = shapeClasses;
        this.wild = wild;
        this.local = local;
        this.source = source;
        this.immune = immune;
        this.poolTagIds = List.copyOf(poolTagIds);
    }

    /** 尚未构建出索引时的占位（客户端标签还没同步到位）。 */
    public static MutationIndex empty() {
        ShapeClasses shapeClasses = ShapeClasses.empty();
        return new MutationIndex(shapeClasses, ClassifiedPool.empty("", shapeClasses),
                new Block[0][], new boolean[0], Set.of(), List.of());
    }

    public ShapeClasses shapeClasses() {
        return shapeClasses;
    }

    /** 本维度的大池（"总池"）：按概率 {@code wild_chance} 命中的那个池。 */
    public ClassifiedPool wild() {
        return wild;
    }

    public boolean isEmpty() {
        return local.length == 0;
    }

    /** 本方块所属全部语义池的并集（已按形态类切分）的候选数量；没有可用池时为 0。 */
    public int localCount(Block block) {
        int id = BuiltInRegistries.BLOCK.getId(block);
        return id >= 0 && id < local.length ? local[id].length : 0;
    }

    /** 局部候选：调用方保证 {@code index < localCount(block)}。 */
    public Block local(Block block, int index) {
        return local[BuiltInRegistries.BLOCK.getId(block)][index];
    }

    public Block[] localCandidates(Block block) {
        int id = BuiltInRegistries.BLOCK.getId(block);
        return id >= 0 && id < local.length ? local[id] : NO_CANDIDATES;
    }

    /** 局部并集是否包含该候选（自检/诊断用，线性扫描，不要放进热路径）。 */
    public boolean localContains(Block source, Block candidate) {
        for (Block block : localCandidates(source)) {
            if (block == candidate) {
                return true;
            }
        }
        return false;
    }

    /** 形态类编号（{@link ShapeClasses#CUBE} / {@link ShapeClasses#NONE} / 自定义类）。 */
    public int shapeClass(Block block) {
        return shapeClasses.of(block);
    }

    /** 该方块是否会失焦。原来是一串逐状态判定，现在是一次 {@code boolean[]} 读。 */
    public boolean isSource(Block block) {
        int id = BuiltInRegistries.BLOCK.getId(block);
        return id >= 0 && id < source.length && source[id];
    }

    /** 永久豁免集合（{@code mutation_immune}）：既不做源也不做目标。 */
    public Set<Block> immune() {
        return immune;
    }

    /** 本维度参与的全部语义池标签 ID（诊断/命令用）。 */
    public List<String> poolTagIds() {
        return poolTagIds;
    }

    /**
     * 任意方块标签按形态类切分后的候选池（引导模型的概念邻域、命令诊断用）。
     * 结果缓存，标签更新时随整个索引一起丢弃。
     */
    public ClassifiedPool tagged(String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return ClassifiedPool.empty("", shapeClasses);
        }
        ClassifiedPool cached = tagPools.get(tagId);
        if (cached != null) {
            return cached;
        }
        ClassifiedPool built = ClassifiedPool.of(tagId, shapeClasses, this::acceptable);
        ClassifiedPool previous = tagPools.putIfAbsent(tagId, built);
        return previous != null ? previous : built;
    }

    /** 池成员过滤：空气、带方块实体的、免疫方块都不是候选，两端必须完全一致。 */
    public boolean acceptable(Block block) {
        return block != Blocks.AIR && !block.defaultBlockState().hasBlockEntity() && !immune.contains(block);
    }
}
