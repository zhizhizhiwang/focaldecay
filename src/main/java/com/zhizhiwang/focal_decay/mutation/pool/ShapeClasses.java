package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 形态类登记表（2026-09-15）：把"几何上能互相替换"的方块分到互斥的等价类里。
 * <p>
 * 突变关系的第二条门控（第一条是语义池）：<b>目标方块必须与源方块同形态类</b>。
 * 楼梯只会变成楼梯、半砖只会变成半砖、栏杆只会变成栏杆，因此不会出现
 * "半砖突变后旁边悬空""栏杆变成完整方块导致连接逻辑失效"这类几何破坏。
 * <p>
 * 类来源分两层：
 * <ol>
 *   <li><b>数据包标签</b> {@code focal_decay:shape_class/*}——运行时唯一的登记方式，
 *       按标签 ID 字典序决定类编号，因此两端一致；</li>
 *   <li><b>自动兜底</b>——没被任何标签收走的方块，用
 *       {@code defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)}
 *       判为 {@link #CUBE}，否则 {@link #NONE}（不参与）。
 *       这里的判定与原版 {@code BlockBehaviour.BlockStateBase.Cache#isCollisionShapeFullBlock}
 *       用的是同一套算法（同一空 BlockGetter、同一原点），但<b>逐方块只算一次</b>并缓存在数组里，
 *       热路径上连形状查询都省掉了。</li>
 * </ol>
 * <b>双格方块守卫</b>：门、高花、床这类占用两格的方块会被强制剔出形态类（只打一条汇总警告）。
 * 原因是突变是逐坐标的纯函数，上下两半各自独立抽取就会抽出两种不同的门。
 * 要支持它们必须引入"锚半格"种子与配对写入，属于单独的一块工作；未实现前不放进池里更安全。
 * 判定方式是方块状态里带 {@link DoubleBlockHalf} 或 {@link BedPart} 取值的属性，
 * 与具体方块类无关，因此模组方块同样适用。
 */
public final class ShapeClasses {

    /** 完整方块：默认的形态类，也是唯一自动判定的一个。 */
    public static final int CUBE = 0;
    /** 不参与突变：非完整方块且没被任何形态类标签收走。 */
    public static final int NONE = 1;
    /** 自定义形态类的起始编号。 */
    public static final int FIRST_CUSTOM = 2;

    public static final String TAG_PATH_PREFIX = "shape_class/";

    private static final ShapeClasses EMPTY = new ShapeClasses(new String[]{"cube", "none"}, new int[0], 2);

    private final String[] names;
    private final int[] byBlockId;
    private final int count;

    private ShapeClasses(String[] names, int[] byBlockId, int count) {
        this.names = names;
        this.byBlockId = byBlockId;
        this.count = count;
    }

    /** 空登记表：所有方块都是 {@link #NONE}（用于客户端还没收到标签时的自愈）。 */
    public static ShapeClasses empty() {
        return EMPTY;
    }

    /** 形态类总数（含 {@link #CUBE} 与 {@link #NONE}）。 */
    public int count() {
        return count;
    }

    /** 类名，用于诊断输出（{@code cube} / {@code none} / 标签路径）。 */
    public String name(int shapeClass) {
        return shapeClass >= 0 && shapeClass < names.length ? names[shapeClass] : "?";
    }

    /** 类名对应的完整标签 ID；{@code cube}/{@code none} 没有标签，返回空串。 */
    public String tagId(int shapeClass) {
        if (shapeClass < FIRST_CUSTOM || shapeClass >= names.length) {
            return "";
        }
        return ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, TAG_PATH_PREFIX + names[shapeClass]).toString();
    }

    /** 方块所属形态类；越界返回 {@link #NONE}。 */
    public int of(int blockId) {
        return blockId >= 0 && blockId < byBlockId.length ? byBlockId[blockId] : NONE;
    }

    public int of(Block block) {
        return of(BuiltInRegistries.BLOCK.getId(block));
    }

    /** 是否参与突变（形态类不是 {@link #NONE}）。 */
    public boolean participates(int blockId) {
        return of(blockId) != NONE;
    }

    /** 从数据包标签重建。调用方负责在标签更新时丢弃旧实例。 */
    public static ShapeClasses build() {
        Registry<Block> registry = BuiltInRegistries.BLOCK;
        List<TagKey<Block>> tags = new ArrayList<>();
        registry.getTagNames()
                .filter(tag -> tag.location().getNamespace().equals(FocalDecay.MODID))
                .filter(tag -> tag.location().getPath().startsWith(TAG_PATH_PREFIX))
                .forEach(tags::add);
        // 标签集合的迭代顺序不保证跨 JVM 一致，类编号必须自己定序，否则两端会抽到不同的目标。
        tags.sort(Comparator.comparing(tag -> tag.location().toString()));

        int blockCount = registry.size();
        List<String> names = new ArrayList<>(List.of("cube", "none"));
        int[] byBlockId = new int[blockCount];
        Arrays.fill(byBlockId, -1); // -1 = 尚未归属，最后统一走自动兜底

        int doubleBlockRefused = 0;
        int multiClass = 0;
        for (TagKey<Block> tag : tags) {
            List<Block> members = new ArrayList<>();
            registry.getTag(tag).ifPresent(holders -> holders.forEach(holder -> members.add(holder.value())));
            if (members.isEmpty()) {
                continue;
            }
            int classId = names.size();
            int accepted = 0;
            for (Block block : members) {
                int id = registry.getId(block);
                if (id < 0 || id >= blockCount || byBlockId[id] != -1) {
                    multiClass += id >= 0 && id < blockCount ? 1 : 0; // 已归入字典序更靠前的类：先到先得
                    continue;
                }
                if (isDoubleBlock(block)) {
                    doubleBlockRefused++;
                    continue;
                }
                byBlockId[id] = classId;
                accepted++;
            }
            if (accepted == 0) {
                continue; // 整类都被守卫剔除（典型：#minecraft:doors）
            }
            names.add(tag.location().getPath().substring(TAG_PATH_PREFIX.length()));
        }

        int fallbackCubes = 0;
        for (Block block : registry) {
            int id = registry.getId(block);
            if (id < 0 || id >= blockCount || byBlockId[id] != -1) {
                continue;
            }
            if (isFullCube(block) && !isDoubleBlock(block)) {
                byBlockId[id] = CUBE;
                fallbackCubes++;
            } else {
                byBlockId[id] = NONE;
            }
        }

        if (doubleBlockRefused > 0) {
            FocalDecay.LOGGER.warn("[focal_decay] shape classes: refused {} double-block entries"
                    + " (doors/tall plants/beds need paired mutation, not supported yet)", doubleBlockRefused);
        }
        if (multiClass > 0) {
            FocalDecay.LOGGER.warn("[focal_decay] shape classes: {} blocks are listed in more than one class,"
                    + " the lexicographically first tag wins", multiClass);
        }
        FocalDecay.LOGGER.info("[focal_decay] shape classes: {} classes ({} cube by fallback)",
                names.size(), fallbackCubes);
        return new ShapeClasses(names.toArray(new String[0]), byBlockId, names.size());
    }

    /** 完整方块判定，与原版逐状态缓存用的是同一套算法（{@code EmptyBlockGetter} + 原点）。 */
    private static boolean isFullCube(Block block) {
        BlockState state = block.defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** 双格方块：状态定义里存在取值为 {@link DoubleBlockHalf} 或 {@link BedPart} 的属性。 */
    private static boolean isDoubleBlock(Block block) {
        for (Property<?> property : block.defaultBlockState().getProperties()) {
            Class<?> valueClass = property.getValueClass();
            if (valueClass == DoubleBlockHalf.class || valueClass == BedPart.class) {
                return true;
            }
        }
        return false;
    }
}
