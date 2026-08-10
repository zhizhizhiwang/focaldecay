package com.zhizhiwang.focal_decay.mutation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 区域覆盖（设计大纲 §4.2）：以控制器为中心、切比雪夫距离 radius 内的方块，
 * 其突变目标从匹配的标签子池中选择。
 * 多个覆盖重叠时取交集；交集为空则回退全局池。
 */
public class RegionOverride {
    public static final int MAX_RADIUS = 32;

    private final BlockPos center;
    private final int radius;
    private final String tagExpression;
    /** 编译后的标签集合（原始标签表达式按分隔符拆分并解析）。 */
    private final Set<TagKey<Block>> tags;

    public RegionOverride(BlockPos center, int radius, String tagExpression, HolderLookup.RegistryLookup<Block> blockLookup) {
        this.center = center.immutable();
        this.radius = Math.max(1, Math.min(MAX_RADIUS, radius));
        this.tagExpression = tagExpression == null ? "" : tagExpression;
        this.tags = compileTags(this.tagExpression, blockLookup);
    }

    public BlockPos center() {
        return center;
    }

    public int radius() {
        return radius;
    }

    public String tagExpression() {
        return tagExpression;
    }

    public Set<TagKey<Block>> tags() {
        return tags;
    }

    public boolean contains(BlockPos pos) {
        return Math.max(Math.abs(pos.getX() - center.getX()),
                Math.max(Math.abs(pos.getY() - center.getY()), Math.abs(pos.getZ() - center.getZ()))) <= radius;
    }

    /** 将标签表达式（逗号分隔，如 "*ore*,minecraft:logs"）解析为 TagKey 集合。 */
    private static Set<TagKey<Block>> compileTags(String expression, HolderLookup.RegistryLookup<Block> blockLookup) {
        if (expression == null || expression.isBlank()) {
            return Collections.emptySet();
        }
        Set<TagKey<Block>> result = new HashSet<>();
        for (String part : expression.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 支持通配符路径，如 minecraft:* 或 *ore* —— 展开注册表中匹配的标签
            if (trimmed.contains("*")) {
                Pattern regex = wildcardPattern(trimmed);
                blockLookup.listTags().forEach(named -> {
                    if (regex.matcher(named.key().location().toString()).matches()) {
                        result.add(named.key());
                    }
                });
            } else {
                try {
                    ResourceLocation id = ResourceLocation.parse(trimmed);
                    TagKey<Block> tagKey = TagKey.create(net.minecraft.core.registries.Registries.BLOCK, id);
                    if (blockLookup.get(tagKey).isPresent()) {
                        result.add(tagKey);
                    }
                } catch (Exception ignored) {
                    // 非法标签名，忽略
                }
            }
        }
        return result;
    }

    private static Pattern wildcardPattern(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    // ---- 序列化 ----
    private static final String TAG_CENTER = "Center";
    private static final String TAG_RADIUS = "Radius";
    private static final String TAG_EXPRESSION = "TagExpression";

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_CENTER, center.asLong());
        tag.putInt(TAG_RADIUS, radius);
        tag.putString(TAG_EXPRESSION, tagExpression);
        return tag;
    }

    public static RegionOverride load(CompoundTag tag, HolderLookup.Provider registries) {
        BlockPos center = BlockPos.of(tag.getLong(TAG_CENTER));
        int radius = tag.getInt(TAG_RADIUS);
        String expression = tag.getString(TAG_EXPRESSION);
        return new RegionOverride(center, radius, expression, registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK));
    }
}
