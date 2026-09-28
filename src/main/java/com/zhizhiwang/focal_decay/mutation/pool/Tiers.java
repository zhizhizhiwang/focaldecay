package com.zhizhiwang.focal_decay.mutation.pool;

import com.zhizhiwang.focal_decay.data.tags.ModTags;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * tier（<b>获得门槛</b>）表 —— 设计见 {@code DESIGN.md} §13.9，方案与裁定见
 * {@code docs/REVIEW-gameplay-spine.md} §3.1–§3.3。
 * <p>
 * <b>为什么需要它</b>：失焦原本只受"形态类"约束，于是石头可以变成下界合金块。
 * tier 表达的是"要多少文明才能拿到它"，护栏只有一条规则——<b>自然失焦只降不升</b>
 * （{@code tier(target) ≤ tier(source)}），唯一的例外是引导/催化下 10% 的小概率跨一级
 * （见 {@link #maxTargetTier}）。
 * <p>
 * <b>为什么不用手写一张大表</b>：tier 有两级来源，只有一处需要人维护。
 * <ol>
 *   <li><b>覆盖标签优先</b>（{@code focal_decay:tier_override/t0..t3}）：用于工具门槛
 *       表达不了的那几类——染色装饰（要染料）、陶瓦（只在恶地生成）、羊毛（要养羊）、
 *       黑曜石（水+岩浆就能刷，按工具门槛虚高）、以及"挖着容易、得到很难"的合成品
 *       （信标要下界之星，但挖它不需要任何等级的镐子）。</li>
 *   <li><b>其余全部推出</b>：工具门槛（{@code needs_stone/iron/diamond_tool}）+ <b>压缩形态 +1</b>。
 *       后者是"1 个方块 = 9 个原料"的那一类：材料块（{@code c:storage_blocks/*}，含粗矿块）
 *       与少量补充（石英族，见 {@code focal_decay:compressed_extra}）。
 *       {@code raw_*_block → *_block} 并不值 9 倍（9 粗铁熔炼正好是 1 个铁块），
 *       只值"免一次冶炼"；真正的 9 倍白赚是"原矿 → 压缩形态"，而 +1 正好把它挡住。</li>
 * </ol>
 * <p>
 * <b>为什么 T4 只有一个成员</b>：压缩形态 = 原矿 + 1，而原矿里门槛最高的只有
 * {@code ancient_debris}(T3)，于是 {@code netherite_block} 落在 T4。自然失焦的源最高只到 T3，
 * 所以它<b>只能由沉降仪式产出</b>——这正是设计要的效果（{@code DESIGN.md} §13.8）。
 * <p>
 * 本类只在<b>索引构建期</b>运行（毫秒级，两端各自从同一份数据包构建），
 * 运行时热路径只读 {@link MutationIndex#tier} 那个 {@code byte[]}。
 */
public final class Tiers {

    /** 最高的一档：压缩形态。自然失焦够不到它（见 {@link #maxTargetTier}）。 */
    public static final int MAX_TIER = 4;
    /** 自然失焦的目标上限。四档覆盖标签是 0..{@code MAX_NATURAL_TIER}。 */
    public static final int MAX_NATURAL_TIER = 3;

    /** NeoForge 通用材料块标签族的前缀：{@code c:storage_blocks/<材料>}，含粗矿块。 */
    private static final String STORAGE_BLOCK_PREFIX = "storage_blocks/";
    private static final String COMMON_NAMESPACE = "c";

    /**
     * "引导 / 催化下跨一级"的随机流用的盐。
     * <p>
     * <b>为什么单独一条流</b>：主随机流（{@code MutationRandom.seed(pos, seed, period)}）的每一个
     * 消耗步都决定目标的选取。如果跨级判定也从主流里取一步，那么"打开 tier 护栏"会连带改变
     * <b>所有</b>已存在世界的方块外观，A/B 也就无法把"护栏"与"随机流位移"两件事分开。
     * 用一条独立流，护栏的开关只影响护栏本身。
     * <p>
     * 取值是 SplitMix64 的黄金比例常数，与任何位置/种子的组合都不会明显相关。
     */
    public static final long UP_TIER_SALT = 0x9E3779B97F4A7C15L;

    /**
     * A/B 开关（{@code -Dfocaldecay.abNoTier=true}）：关掉护栏，回到"只看形态类"的旧行为。
     * <p>
     * 用途只有一个——证明 {@code [tier]} 段的断言真的会因为护栏消失而 FAIL。
     * 系统属性在 JVM 启动时读取即可：这个开关不需要在运行中翻转。
     */
    private static volatile boolean gateDisabled = Boolean.getBoolean("focaldecay.abNoTier");

    private Tiers() {
    }

    /** 护栏是否被 A/B 开关关掉（关掉时 {@code resolve} 不做任何 tier 判定）。 */
    public static boolean gateDisabled() {
        return gateDisabled;
    }

    /**
     * <b>只给 {@code /focaldecay mutation selftest} 的 A/B 用</b>：让同一份日志里同时出现
     * "有护栏"与"无护栏"两个数字。
     * <p>
     * 为什么值得在生产代码里留一个测试开关：没有它，{@code [tier]} 的主断言与它的<b>控制组</b>
     * 只能分两次运行各看一半；而"护栏没生效"与"池里本来就没有越级目标"这两种情况的日志
     * <b>长得一模一样</b>（都是 0 次越级），后者会让主断言变成一条永远 PASS 的空断言。
     * 初值仍然来自 {@code -Dfocaldecay.abNoTier}，两次运行的 A/B 照旧可用。
     */
    public static void setGateDisabledForTest(boolean disabled) {
        gateDisabled = disabled;
    }

    /**
     * 一次解析的目标 tier 上限（纯函数）：
     * <b>只降不升</b>，外加"引导/催化下跨一级"，并且永远封顶在 {@link #MAX_NATURAL_TIER}。
     * <p>
     * 封顶那一项有两个作用：一是让 {@code netherite_block}(T4) 无法从任何源产出（只能靠仪式）；
     * 二是让 T4 的方块（玩家自己合成的下界合金块）也会向下崩坏，而不是停留在 T4。
     */
    public static int maxTargetTier(int sourceTier, boolean upTier) {
        return Math.min(sourceTier + (upTier ? 1 : 0), MAX_NATURAL_TIER);
    }

    /**
     * 构建 tier 表（按注册表 id 索引）。
     * <p>
     * 覆盖标签重叠时取<b>高</b>的一档：两种错误的代价不对称——高估只会让某个方块更难被产出，
     * 低估会让它提前出现在早期世界里，而后者正是 tier 要防的事。
     */
    public static byte[] build(Registry<Block> registry) {
        int n = registry.size();
        byte[] tier = new byte[n];
        boolean[] overridden = new boolean[n];

        for (int slot = 0; slot <= MAX_NATURAL_TIER; slot++) {
            for (Block block : members(registry, ModTags.Blocks.tierOverride(slot))) {
                int id = registry.getId(block);
                if (id < 0 || id >= n) {
                    continue;
                }
                if (overridden[id]) {
                    tier[id] = (byte) Math.max(tier[id], slot);
                } else {
                    tier[id] = (byte) slot;
                    overridden[id] = true;
                }
            }
        }

        Set<Block> compressed = compressedMembers(registry);
        for (Block block : registry) {
            int id = registry.getId(block);
            if (id < 0 || id >= n || overridden[id]) {
                continue;
            }
            int base = toolTier(block);
            tier[id] = (byte) Math.min(MAX_TIER, base + (compressed.contains(block) ? 1 : 0));
        }
        return tier;
    }

    /** 工具门槛：原版自己给出的"要多少等级的镐子"。 */
    public static int toolTier(Block block) {
        var state = block.defaultBlockState();
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return 3;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return 2;
        }
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            return 1;
        }
        return 0;
    }

    /** 压缩形态的成员集合：{@code c:storage_blocks/*} 全体 + 手写补充。 */
    public static Set<Block> compressedMembers(Registry<Block> registry) {
        Set<Block> out = new HashSet<>();
        registry.getTagNames()
                .filter(Tiers::isStorageBlockTag)
                .forEach(tag -> registry.getTag(tag)
                        .ifPresent(holders -> holders.forEach(holder -> out.add(holder.value()))));
        out.addAll(members(registry, ModTags.Blocks.COMPRESSED_EXTRA));
        return out;
    }

    private static boolean isStorageBlockTag(TagKey<Block> tag) {
        ResourceLocation id = tag.location();
        return id.getNamespace().equals(COMMON_NAMESPACE) && id.getPath().startsWith(STORAGE_BLOCK_PREFIX);
    }

    private static List<Block> members(Registry<Block> registry, TagKey<Block> tag) {
        List<Block> out = new ArrayList<>();
        registry.getTag(tag).ifPresent(holders -> holders.forEach(holder -> out.add(holder.value())));
        return out;
    }

    /** 某一档的名字（日志与自测用）。 */
    public static String label(int tier) {
        return "T" + tier;
    }

    /**
     * 各档成员数的一行摘要（构建期日志与 {@code mutation audit} 用）。
     * 刻意做成"看得见分布"而不是只报总数：tier 表最容易出的问题是某一档被塞爆
     * （例如工具门槛把 76 条廉价铜装饰全判成 T1），而那在总数上看不出来。
     */
    public static String histogram(byte[] tier) {
        int[] counts = new int[MAX_TIER + 1];
        for (byte value : tier) {
            if (value >= 0 && value <= MAX_TIER) {
                counts[value]++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t <= MAX_TIER; t++) {
            if (t > 0) {
                sb.append(' ');
            }
            sb.append(label(t)).append('=').append(counts[t]);
        }
        return sb.toString();
    }
}
