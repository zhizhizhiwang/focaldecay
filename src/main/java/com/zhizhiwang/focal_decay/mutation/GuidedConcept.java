package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
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
 * 引导模型的概念解析与语义邻域（方案 A，2026-08-21，PROXYAI §4.2）。
 * <p>
 * 概念 = 训练目标覆盖率最高的语义标签（策展 {@code focal_decay:concept/*} 优先，
 * 兜底原版标签、排除通用标签黑名单）；概念邻域 = 标签下全部有效方块
 * （过滤空气/带方块实体/转换黑名单），按注册表 id 升序保证两端一致。
 * 完备度 q 只作用于目标选择，不改变阶段突变骰子。
 * 方块是静态注册表：两端共用 {@link BuiltInRegistries#BLOCK}，确定性一致。
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
     */
    public static Concept resolve(List<String> trainedTargets) {
        List<Block> trained = parseBlocks(trainedTargets);
        if (trained.isEmpty()) {
            return INVALID;
        }
        Concept best = bestConcept(trained, ModTags.Blocks.curatedConcepts());
        if (best == null) {
            best = bestConcept(trained, fallbackCandidates(trained));
        }
        return best == null ? INVALID : best;
    }

    private static List<TagKey<Block>> fallbackCandidates(List<Block> trained) {
        Set<TagKey<Block>> candidates = new HashSet<>();
        BuiltInRegistries.BLOCK.getTagNames().forEach(tag -> {
            if (ModTags.Blocks.isCurated(tag) || isGeneric(tag)) {
                return;
            }
            if (neighborhood(tag.location().toString()).size() > MAX_FALLBACK_CONCEPT_SIZE) {
                return;
            }
            for (Block block : trained) {
                if (isMember(block, tag.location().toString())) {
                    candidates.add(tag);
                    break;
                }
            }
        });
        List<TagKey<Block>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(tag -> tag.location().toString()));
        return sorted;
    }

    private static Concept bestConcept(List<Block> trained, List<TagKey<Block>> tags) {
        Concept best = null;
        for (TagKey<Block> tag : tags) {
            List<Block> members = neighborhood(tag.location().toString());
            if (members.isEmpty()) {
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
            double coverage = (double) trainedIn / members.size();
            Concept candidate = new Concept(tag.location().toString(), computeQ(trainedIn, coverage), trainedIn, members.size());
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
        double q = storedQ;
        if (stage >= 3 && FocalDecayConfig.GUIDED_STAGE3_HALVE.get()) {
            q *= 0.5;
        }
        return Math.max(0.0, Math.min(1.0, q));
    }

    /** 概念标签的"有效目标"成员列表：按注册表 id 升序（两端一致，供目标选择索引）。 */
    public static List<Block> neighborhood(String tagId) {
        List<Block> blocks = new ArrayList<>();
        if (tagId.isEmpty()) {
            return blocks;
        }
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId));
        BuiltInRegistries.BLOCK.getTag(tag).ifPresent(holders -> holders.forEach(holder -> {
            Block block = holder.value();
            if (isValidTarget(block)) {
                blocks.add(block);
            }
        }));
        blocks.sort(Comparator.comparingInt(BuiltInRegistries.BLOCK::getId));
        return blocks;
    }

    /** 源门控：方块是否属于概念标签。 */
    public static boolean isMember(Block block, String tagId) {
        if (tagId.isEmpty()) {
            return false;
        }
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId));
        return BuiltInRegistries.BLOCK.getTag(tag)
                .map(holders -> holders.stream().anyMatch(holder -> holder.value() == block))
                .orElse(false);
    }

    /** 有效目标：非空气、无方块实体、不在转换黑名单。 */
    public static boolean isValidTarget(Block block) {
        return block != Blocks.AIR
                && !block.defaultBlockState().hasBlockEntity()
                && !block.defaultBlockState().is(ModTags.Blocks.CONVERSION_BLACKLIST);
    }

    /** 概念显示名：优先语言键（tag.block.命名空间.路径），缺失回退原始 ID。 */
    public static Component displayName(String tagId) {
        if (tagId.isEmpty()) {
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
}
