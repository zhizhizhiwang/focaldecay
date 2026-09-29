package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.mutation.pool.ClassifiedPool;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引导模型的概念解析与语义邻域（方案 A，2026-08-21）。
 * <p>
 * 概念 = 训练目标覆盖率最高的语义标签（策展 {@code focal_decay:concept/*} 优先，
 * 兜底原版标签、排除通用标签黑名单）；概念邻域 = 标签下全部有效方块
 * （过滤空气/带方块实体/转换黑名单/不参与突变的形态类），按注册表 id 升序保证两端一致。
 * 完备度 q 只作用于目标选择，不改变阶段突变骰子。
 * <p>
 * 2026-09-15 起邻域取自 {@link MutationIndex#tagged(String)} 的缓存池：原来
 * {@code isMember} 每次都要解析 ID、查标签、再线性扫一遍标签成员，而 {@code neighborhood}
 * 每次都要新建并排序一个列表——这两个函数都在逐方块扫描的循环里，属于必须先拔掉的性能债。
 * 现在成员判定是一次 {@code boolean[]} 读，邻域是现成的数组。
 * <p>
 * 概念是<b>训练时</b>一次性解析并固化进模型数据的（热点只在"完成训练"那一刻），
 * 所以本文件的解析逻辑不在于快，在于两端与存档之间可复现。
 */
public final class GuidedConcept {

    /** 兜底解析时排除的通用标签（"太泛化"的标签不能指认概念）。 */
    private static final Set<String> GENERIC_TAGS = Set.of(
            "minecraft:block",
            "minecraft:air",
            "minecraft:replaceable",
            "minecraft:replaceable_by_trees",
            "minecraft:enchantment_power_provider",
            "minecraft:enchantment_power_transmitter",
            "minecraft:maintains_farmland",
            "minecraft:inside_step_sound_blocks",
            "minecraft:soul_speed_blocks",
            "minecraft:climbable",
            "minecraft:features_cannot_replace",
            "minecraft:lava_pool_stone_cannot_replace",
            "minecraft:geode_invalid_blocks",
            "minecraft:sculk_replaceable",
            "minecraft:sculk_replaceable_world_gen",
            "minecraft:moss_replaceable",
            "minecraft:lush_ground_replaceable",
            "minecraft:dripstone_replaceable_blocks",
            "minecraft:overworld_carver_replaceables",
            "minecraft:nether_carver_replaceables",
            "minecraft:sniffer_diggable_block",
            "minecraft:valid_spawn",
            "minecraft:impermeable"
    );

    /** 兜底候选标签的成员数上限：超过视为"过于泛化"，排除。 */
    private static final int MAX_FALLBACK_CONCEPT_SIZE = 64;

    /**
     * 概念解析结果。
     *
     * @param tagId         概念标签 ID（{@code ""} = 无有效概念）
     * @param q             完备度，见 {@link #computeQ}
     * @param trainedBlocks 落在该概念里、<b>且拿得到</b>的已记录目标数（分子）
     * @param conceptSize   该概念的<b>全部</b>成员数（诊断用；界面上"该概念共 N 种"用的是它）
     * @param trainableSize 其中拿得到的成员数，即封顶前的分母。界面必须照实显示这个数：
     *                      霜冰那类没有 {@code BlockItem} 的方块永远凑不齐，
     *                      玩家看不见它就会以为"还差一种"而白找
     */
    public record Concept(String tagId, double q, int trainedBlocks, int conceptSize, int trainableSize) {
        public boolean valid() {
            return !tagId.isEmpty() && q > 0.0;
        }

        /** 该完备度解锁到哪一档（见 {@link #unlockFor}）。 */
        public Unlock unlock() {
            return unlockFor(q);
        }
    }

    public static final Concept INVALID = new Concept("", 0.0, 0, 0, 0);

    /**
     * q 的四个门槛（2026-09-29，{@code DESIGN.md} §13.10）：
     * 每一级给一个<b>新的动词</b>，而不是一个更大的数字。
     * <p>
     * 判据用<b>存下来的 q</b>（玩家的理解），不是 {@link #effectiveQ 效果期 q}——
     * 阶段 3 的效果衰减砍的是"引导有多灵"，不该把已经学会的东西收回去。
     */
    public enum Unlock {
        /** 还没有概念。 */
        NONE("concept.focal_decay.unlock_none", "recorded"),
        /** 看得见：该概念下的漂移在画面上被标出来。 */
        SEE("concept.focal_decay.unlock_see", "visible"),
        /** 让它变：可点火催化域。 */
        CATALYSE("concept.focal_decay.unlock_catalyse", "can catalyse"),
        /** 让它产出：可做沉降仪式。 */
        RITE("concept.focal_decay.unlock_rite", "can perform the rite"),
        /** 说了算：点火时可点名概念里的某一种方块。 */
        DICTATE("concept.focal_decay.unlock_dictate", "can dictate the target");

        private final String langKey;
        private final String fallback;

        Unlock(String langKey, String fallback) {
            this.langKey = langKey;
            this.fallback = fallback;
        }

        public String langKey() {
            return langKey;
        }

        public Component displayName() {
            return Component.translatableWithFallback(langKey, fallback);
        }

        public boolean atLeast(Unlock other) {
            return ordinal() >= other.ordinal();
        }
    }

    /** 门槛，下标与 {@link Unlock} 的序号一一对应（自测会断言两者同长）。 */
    public static final double[] UNLOCK_THRESHOLDS = {0.0, 0.25, 0.50, 0.75, 1.0};

    /**
     * q → 解锁档（纯函数，带 0.5% 容差：界面显示的百分比是四舍五入的，
     * 玩家看到 100% 时实际值允许差一丝）。
     */
    public static Unlock unlockFor(double q) {
        Unlock best = Unlock.NONE;
        Unlock[] all = Unlock.values();
        for (int i = 0; i < UNLOCK_THRESHOLDS.length && i < all.length; i++) {
            if (q + 5.0e-3 >= UNLOCK_THRESHOLDS[i]) {
                best = all[i];
            }
        }
        return best;
    }

    private GuidedConcept() {
    }

    /**
     * 训练完成时解析概念：优先策展概念标签（覆盖率最高者胜出，并列取标签 ID 字典序更小者）；
     * 无策展命中时兜底用训练方块的普通标签（排除黑名单与过泛化标签）。
     *
     * @param index 该维度的突变查表，提供"标签 → 按形态类切分的邻域"的缓存
     */
    public static Concept resolve(List<String> trainedTargets, MutationIndex index) {
        List<Block> trained = parseBlocks(trainedTargets);
        if (trained.isEmpty()) {
            return INVALID;
        }
        Concept best = bestConcept(trained, ModTags.Blocks.curatedConcepts(), index);
        if (best == null) {
            best = bestConcept(trained, fallbackCandidates(trained, index), index);
        }
        return best == null ? INVALID : best;
    }

    private static List<TagKey<Block>> fallbackCandidates(List<Block> trained, MutationIndex index) {
        Set<TagKey<Block>> candidates = new HashSet<>();
        BuiltInRegistries.BLOCK.getTagNames().forEach(tag -> {
            if (ModTags.Blocks.isCurated(tag) || isGeneric(tag)) {
                return;
            }
            if (index.tagged(tag.location().toString()).total() > MAX_FALLBACK_CONCEPT_SIZE) {
                return;
            }
            for (Block block : trained) {
                if (index.tagged(tag.location().toString()).contains(block)) {
                    candidates.add(tag);
                    break;
                }
            }
        });
        List<TagKey<Block>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(tag -> tag.location().toString()));
        return sorted;
    }

    private static Concept bestConcept(List<Block> trained, List<TagKey<Block>> tags, MutationIndex index) {
        Concept best = null;
        for (TagKey<Block> tag : tags) {
            ClassifiedPool members = index.tagged(tag.location().toString());
            int size = members.total();
            if (size == 0) {
                continue;
            }
            // 分子与分母同口径：都只算"拿得到"的成员。否则记录到霜冰会出现
            // "已记录 4 / 需要 3" 这种自相矛盾的显示（而且它还白占了分数）。
            int trainedIn = 0;
            for (Block block : trained) {
                if (members.contains(block) && isTrainable(block)) {
                    trainedIn++;
                }
            }
            if (trainedIn <= 0) {
                continue;
            }
            int trainable = trainableCount(members);
            Concept candidate = new Concept(tag.location().toString(), computeQ(trainedIn, trainable),
                    trainedIn, size, trainable);
            if (best == null || better(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean better(Concept a, Concept b) {
        if (a.q() != b.q()) {
            return a.q() > b.q();
        }
        return a.tagId().compareTo(b.tagId()) < 0;
    }

    /**
     * 完备度 q（2026-09-29 改，{@code DESIGN.md} §13.10）：<b>上凸曲线</b>
     * {@code q = sqrt(已记录 / 需要)}。
     * <p>
     * <b>为什么上凸</b>：30 个成员里记录 15 个就该有 70%（{@code sqrt(0.5) = 0.707}），
     * 但 100% 仍然要求把整个概念记完。于是"早解锁来得快、最后一截很长"——
     * q ≥ 0.25 只要 6% 的成员，q = 1.0 要 100%。线性曲线会让前期太慢（记录下来没手感）、
     * 后期太快（最后几十个百分点毫无意义）。
     * <p>
     * <b>分母的两端补偿</b>（作者 2026-09-29 定案，起因是"霜冰只能靠冰霜行者获得，
     * 不补偿则 ice 概念的 q 永远停在 75%"）：
     * <ol>
     *   <li>排除没有 {@code BlockItem} 的成员（{@link #isTrainable}）——它们凑不齐，
     *       留在分母里等于给概念设了一个够不到的天花板；</li>
     *   <li>再按 {@code guided_q_size_cap} 封顶——大概念（{@code ore} 有三十多种成员）
     *       否则永远练不满，而主线要用它。</li>
     * </ol>
     * 少于 {@code guided_min_trained} 个目标仍视为残缺分类（q = 0）：一个方块不构成概念。
     *
     * @param trainedInConcept 落在概念里、且拿得到的已记录目标数（分子）
     * @param trainableSize    该概念里拿得到的成员数（分母，封顶前的原始值）
     */
    public static double computeQ(int trainedInConcept, int trainableSize) {
        if (trainedInConcept < FocalDecayConfig.GUIDED_MIN_TRAINED.get()) {
            return 0.0;
        }
        int required = Math.min(Math.max(trainableSize, 1), FocalDecayConfig.GUIDED_Q_SIZE_CAP.get());
        double fraction = Math.min((double) trainedInConcept / required, 1.0);
        return Math.sqrt(fraction);
    }

    /**
     * 能不能被"记录"进概念：没有对应物品的方块（霜冰、基岩、刷怪笼…）不该计入分母。
     * <p>
     * 判据用 {@code Block#asItem()}——方块没有 {@code BlockItem} 时它返回 {@code Items.AIR}
     * （{@code PITFALLS.md} §2 那条"用它构造 ItemStack 会得到空栈"的同一个来源）。
     */
    public static boolean isTrainable(Block block) {
        return block.asItem() != Items.AIR;
    }

    /** 一个池里"拿得到"的成员数。只在训练完成/界面计算时调用，不在热路径上。 */
    public static int trainableCount(ClassifiedPool members) {
        int count = 0;
        for (Block block : members.flat()) {
            if (isTrainable(block)) {
                count++;
            }
        }
        return count;
    }

    /** 效果期完备度：阶段 3 按配置减半（服务端与客户端共用，保证预览一致）。 */
    public static double effectiveQ(double storedQ, int stage) {
        return effectiveQ(storedQ, stage, FocalDecayConfig.GUIDED_STAGE3_HALVE.get());
    }

    /**
     * 效果期完备度（纯函数：折半开关显式给出）。
     * <p>
     * 客户端预览走这个重载并传入 {@link MutationSettings} 里的开关值：完备度 q 直接乘在
     * "抽中引导池"的概率上，两端取值不同就会抽出不同的目标。
     */
    public static double effectiveQ(double storedQ, int stage, boolean halveInStage3) {
        double q = storedQ;
        if (stage >= 3 && halveInStage3) {
            q *= 0.5;
        }
        return Math.max(0.0, Math.min(1.0, q));
    }

    /**
     * 多个引导模型同时生效时取哪一个：q 大者胜，<b>q 相等按中心坐标字典序</b>。
     * <p>
     * 决胜规则必须与顺序无关，而且要两端共用：服务端按"登记顺序"遍历效果列表，
     * 客户端按"登录快照 + 单条增量到达顺序"遍历——增量同步之后这两个顺序不保证一致
     * （例如区块重载会让服务端把某个效果移到列表末尾）。若只比 q，两个同概念、同完备度的
     * 引导模型在两台机器上会挑中不同的那个，抽出来的目标自然不同。
     *
     * @param bestPos 当前最优模型中心；{@code null} 表示还没有最优
     */
    public static boolean betterGuided(double q, BlockPos pos, double bestQ, BlockPos bestPos) {
        if (bestPos == null) {
            return true;
        }
        if (q != bestQ) {
            return q > bestQ;
        }
        if (pos.getX() != bestPos.getX()) {
            return pos.getX() < bestPos.getX();
        }
        if (pos.getY() != bestPos.getY()) {
            return pos.getY() < bestPos.getY();
        }
        return pos.getZ() < bestPos.getZ();
    }

    /** 概念显示名：优先语言键（tag.block.命名空间.路径），缺失回退原始 ID。 */
    public static Component displayName(String tagId) {        if (tagId.isEmpty()) {
            return Component.literal("?");
        }
        String key = "tag.block." + tagId.replace(':', '.').replace('/', '.');
        return Component.translatableWithFallback(key, tagId);
    }

    private static boolean isGeneric(TagKey<Block> tag) {
        String id = tag.location().toString();
        if (GENERIC_TAGS.contains(id)) {
            return true;
        }
        String path = tag.location().getPath();
        return path.startsWith("mineable/") || path.startsWith("needs_");
    }

    private static List<Block> parseBlocks(List<String> ids) {
        List<Block> blocks = new ArrayList<>();
        for (String id : ids) {
            try {
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
                if (block != Blocks.AIR) {
                    blocks.add(block);
                }
            } catch (Exception ignored) {
                // 非法 ID 忽略
            }
        }
        return blocks;
    }

    /** 保留：标签 ID → 标签键（诊断/命令用）。 */
    public static TagKey<Block> tagKey(String tagId) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId));
    }
}
