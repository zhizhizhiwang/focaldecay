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

    /** 概念解析结果：概念标签 ID（"" = 无有效概念）+ 完备度 q + 统计。 */
    public record Concept(String tagId, double q, int trainedBlocks, int conceptSize) {
        public boolean valid() {
            return !tagId.isEmpty() && q > 0.0;
        }
    }

    public static final Concept INVALID = new Concept("", 0.0, 0, 0);

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
            int trainedIn = 0;
            for (Block block : trained) {
                if (members.contains(block)) {
                    trainedIn++;
                }
            }
            if (trainedIn <= 0) {
                continue;
            }
            double coverage = (double) trainedIn / size;
            Concept candidate = new Concept(tag.location().toString(), computeQ(trainedIn, coverage), trainedIn, size);
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

    /** 完备度：少于最小训练数视为残缺分类 q=0；否则 coverage × 倍率，封顶。 */
    public static double computeQ(int trainedInConcept, double coverage) {
        if (trainedInConcept < FocalDecayConfig.GUIDED_MIN_TRAINED.get()) {
            return 0.0;
        }
        double q = coverage * FocalDecayConfig.GUIDED_Q_MULTIPLIER.get();
        return Math.max(0.0, Math.min(FocalDecayConfig.GUIDED_Q_CAP.get(), q));
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
