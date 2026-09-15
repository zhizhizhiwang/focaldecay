package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签 → {@link MutationIndex} 的唯一构建者（2026-09-15）。
 * <p>
 * 这里是整个突变系统里<b>唯一</b>知道标签语义的地方：池怎么发现、切片怎么作废、
 * 源门控怎么判定，全部集中在 {@link #build()} 里，运行时不再碰任何标签。
 * <p>
 * 数据包协议（全部位于 {@code focal_decay} 命名空间，任何数据包都可以往里加内容）：
 * <table border="1">
 *   <tr><th>标签</th><th>作用</th></tr>
 *   <tr><td>{@code focal_decay:mutation_pool/<名>}</td>
 *       <td><b>突变池</b>。一个方块可以进任意多个池；抽目标时取它所属全部池的<b>并集</b>
 *           （再与源方块求同形态类）。池内成员只要在该形态类下不足 2 个，这个切片整体作废。</td></tr>
 *   <tr><td>{@code focal_decay:mutation_pool/wild}（下界/末地各有专属变体）</td>
 *       <td><b>大池</b>。以 {@code wild_chance} 的概率<b>整枝</b>命中它，用来保证长尾随机性；
 *           它不是成员关系，所以不会把语义池连成一个连通分量。</td></tr>
 *   <tr><td>{@code focal_decay:shape_class/<名>}</td>
 *       <td><b>形态类</b>。目标必须与源同形态类。</td></tr>
 *   <tr><td>{@code focal_decay:mutation_immune}</td>
 *       <td><b>完全豁免</b>：既不做源也不做目标。</td></tr>
 *   <tr><td>{@code focal_decay:mutation_source_extra}</td>
 *       <td><b>额外源</b>：会失焦，但永远不会被抽成目标（单向，不会造成冻结）。</td></tr>
 * </table>
 */
public final class MutationIndexBuilder {

    /** 池标签的路径前缀。 */
    public static final String POOL_TAG_PREFIX = "mutation_pool/";
    /** 大池及其维度变体的完整标签 ID，发现语义池时要跳过。 */
    private static final Set<String> WILD_TAG_IDS = Set.of(
            ModTags.Blocks.MUTATION_POOL_WILD.location().toString(),
            ModTags.Blocks.MUTATION_POOL_WILD_NETHER.location().toString(),
            ModTags.Blocks.MUTATION_POOL_WILD_END.location().toString());

    private final ResourceKey<Level> dimension;

    public MutationIndexBuilder(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    public MutationIndex build() {
        Registry<Block> registry = BuiltInRegistries.BLOCK;
        ShapeClasses shapeClasses = ShapeClasses.build();
        Set<Block> immune = readMembers(ModTags.Blocks.MUTATION_IMMUNE);

        // ---- 大池 ----
        TagKey<Block> wildTag = ModTags.Blocks.poolForDimension(dimension);
        List<Block> wildMembers = new ArrayList<>(readMembers(wildTag));
        int autoIncluded = 0;
        if (FocalDecayConfig.WILD_AUTO_INCLUDE.get()) {
            // 默认把"当前规则下所有可失焦的完整方块"都算进大池，保证总池足够大且零行为回归。
            Set<Block> present = new HashSet<>(wildMembers);
            for (Block block : registry) {
                if (present.contains(block) || immune.contains(block) || block.defaultBlockState().hasBlockEntity()) {
                    continue;
                }
                if (shapeClasses.of(block) == ShapeClasses.CUBE) {
                    wildMembers.add(block);
                    autoIncluded++;
                }
            }
        }
        ClassifiedPool wild = ClassifiedPool.ofMembers(wildTag.location().toString(), shapeClasses,
                filter(wildMembers, shapeClasses, immune));

        // ---- 语义池 ----
        List<TagKey<Block>> poolTags = discoverPoolTags(registry);
        Map<Block, Set<Block>> unions = new HashMap<>();
        List<String> poolIds = new ArrayList<>();
        int droppedSlices = 0;
        for (TagKey<Block> tag : poolTags) {
            List<Block> members = filter(readMembers(tag), shapeClasses, immune);
            Map<Integer, List<Block>> byClass = new HashMap<>();
            for (Block block : members) {
                byClass.computeIfAbsent(shapeClasses.of(block), k -> new ArrayList<>()).add(block);
            }
            boolean anySlice = false;
            for (Map.Entry<Integer, List<Block>> entry : byClass.entrySet()) {
                List<Block> slice = entry.getValue();
                if (slice.size() < 2) {
                    // 关键不变式：单成员切片会让成员"只能变成自己"，也就是永久冻结，整体丢弃。
                    droppedSlices++;
                    continue;
                }
                Set<Block> sliceSet = new LinkedHashSet<>(slice);
                for (Block block : slice) {
                    unions.computeIfAbsent(block, k -> new LinkedHashSet<>()).addAll(sliceSet);
                }
                anySlice = true;
            }
            if (anySlice) {
                poolIds.add(tag.location().toString());
            }
        }

        // ---- 摊平成数组 ----
        int blockCount = registry.size();
        Block[][] local = new Block[blockCount][];
        Arrays.fill(local, new Block[0]);
        for (Map.Entry<Block, Set<Block>> entry : unions.entrySet()) {
            List<Block> list = new ArrayList<>(entry.getValue());
            // 注册表顺序不保证跨 JVM 稳定，索引必须显式定序，否则两端抽到不同目标。
            list.sort(Comparator.comparingInt(registry::getId));
            local[registry.getId(entry.getKey())] = list.toArray(new Block[0]);
        }

        // ---- 源门控 ----
        boolean[] source = new boolean[blockCount];
        int sourceCount = 0;
        for (Block block : registry) {
            int id = registry.getId(block);
            if (id < 0 || id >= blockCount) {
                continue;
            }
            if (!isEligible(block, shapeClasses, immune)) {
                continue;
            }
            // 池即源：只要进了一个可用切片，或者落在大池的可用切片里，它就会失焦。
            boolean usable = local[id].length > 0 || wild.usable(shapeClasses.of(block), 2);
            if (usable) {
                source[id] = true;
                sourceCount++;
            }
        }

        // ---- 额外源（只出不进）----
        int extraCount = 0;
        for (Block block : readMembers(ModTags.Blocks.MUTATION_SOURCE_EXTRA)) {
            int id = registry.getId(block);
            if (id < 0 || id >= blockCount || source[id] || !isEligible(block, shapeClasses, immune)) {
                continue;
            }
            if (wild.usable(shapeClasses.of(block), 2)) {
                source[id] = true;
                sourceCount++;
                extraCount++;
            }
        }

        FocalDecay.LOGGER.info("[focal_decay] mutation index[{}]: {} pools, wild={} blocks (+{} auto),"
                        + " {} sources (+{} extra), {} slices dropped",
                dimension.location(), poolIds.size(), wild.total(), autoIncluded,
                sourceCount, extraCount, droppedSlices);
        return new MutationIndex(shapeClasses, wild, local, source, immune, poolIds);
    }

    /** 语义池发现：{@code focal_decay:mutation_pool/*}，排除大池，按标签 ID 定序。 */
    private static List<TagKey<Block>> discoverPoolTags(Registry<Block> registry) {
        List<TagKey<Block>> tags = new ArrayList<>();
        registry.getTagNames()
                .filter(tag -> tag.location().getNamespace().equals(FocalDecay.MODID))
                .filter(tag -> tag.location().getPath().startsWith(POOL_TAG_PREFIX))
                .filter(tag -> !WILD_TAG_IDS.contains(tag.location().toString()))
                .forEach(tags::add);
        tags.sort(Comparator.comparing(tag -> tag.location().toString()));
        return tags;
    }

    private static Set<Block> readMembers(TagKey<Block> tag) {
        Set<Block> members = new LinkedHashSet<>();
        BuiltInRegistries.BLOCK.getTag(tag)
                .ifPresent(holders -> holders.forEach(holder -> members.add(holder.value())));
        return members;
    }

    /** 池成员过滤：去掉空气、带方块实体的、免疫方块，以及不参与突变的形态类。 */
    private static List<Block> filter(Iterable<Block> blocks, ShapeClasses shapeClasses, Set<Block> immune) {
        List<Block> result = new ArrayList<>();
        for (Block block : blocks) {
            if (isEligible(block, shapeClasses, immune)) {
                result.add(block);
            }
        }
        return result;
    }

    private static boolean isEligible(Block block, ShapeClasses shapeClasses, Set<Block> immune) {
        if (immune.contains(block) || block.defaultBlockState().hasBlockEntity()) {
            return false;
        }
        return shapeClasses.participates(BuiltInRegistries.BLOCK.getId(block));
    }
}
