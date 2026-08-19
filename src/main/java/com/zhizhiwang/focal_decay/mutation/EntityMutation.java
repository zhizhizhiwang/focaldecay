package com.zhizhiwang.focal_decay.mutation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 实体转换的 NBT 白名单与替换逻辑（设计大纲 §6.4）。
 * <p>
 * 只保留"跨物种仍存在且有意义"的身份/状态字段；飞行、物理、渲染这类瞬态标志一律丢弃，
 * 从根本上避免把 NoGravity / 速度 / 燃烧等源实体状态带进目标实体。
 */
public final class EntityMutation {

    /** 跨物种应保留的字段。 */
    private static final Set<String> PRESERVED_KEYS = Set.of(
            "Age",                  // 幼年/成年（设计明确要求保留年龄）
            "ForcedAge",            // 是否被强制固定年龄
            "Health",               // 当前生命（load 会按目标类型上限钳制）
            "CustomName",           // 自定义名称
            "CustomNameVisible",    // 名称可见性
            "PersistenceRequired",  // 不自然消失
            "Tags",                 // 记分板/身份标签
            "ActiveEffects"         // 药水效果
    );

    private EntityMutation() {
    }

    /**
     * 从源实体构造"转换后保留"的 NBT：
     * 白名单字段 + 位置/朝向，速度（Motion）显式清零。
     */
    public static CompoundTag buildConversionTag(Mob source) {
        CompoundTag full = source.saveWithoutId(new CompoundTag());
        CompoundTag result = new CompoundTag();

        for (String key : PRESERVED_KEYS) {
            if (full.contains(key)) {
                result.put(key, full.get(key).copy());
            }
        }

        // Entity#load 硬性要求这三个键存在，否则 getDouble(0)/getFloat(0) 会越界
        result.put("Pos", full.getList("Pos", 6).copy());
        result.put("Rotation", full.getList("Rotation", 5).copy());

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0));
        motion.add(DoubleTag.valueOf(0.0));
        motion.add(DoubleTag.valueOf(0.0));
        result.put("Motion", motion);

        return result;
    }

    /** 把 mob 替换为 targetType 的实体，仅继承白名单字段与位置/朝向。 */
    public static void convert(ServerLevel level, Mob source, EntityType<?> targetType) {
        CompoundTag tag = buildConversionTag(source);
        Vec3 pos = source.position();
        float yRot = source.getYRot();
        float xRot = source.getXRot();
        source.discard();

        Entity target = targetType.create(level);
        if (target == null) {
            return;
        }
        target.load(tag);
        target.moveTo(pos.x, pos.y, pos.z, yRot, xRot);
        level.addFreshEntity(target);
    }
}
