package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.BuiltInRegistries;
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

    /**
     * 把 mob 替换为 targetType 的实体，仅继承白名单字段与位置/朝向。
     * <p>
     * <b>顺序很重要</b>（2026-09-25 修，BACKLOG `P1-1c`）：必须<b>先建、先 load、确认成功，
     * 最后才 discard 源实体</b>。早先的实现是 {@code source.discard()} 排在
     * {@code targetType.create(...)} 的空值检查之前——于是 create 返回 null（或 load 抛异常）时，
     * 旧实体已经被删、新实体还不存在，<b>生物凭空消失</b>；load 抛异常还会直接逃出 tick 处理器。
     * <p>
     * 失败时保留源实体（即"转换没发生"），而不是留下一个空位。调用方无法区分这两种结果，
     * 但对玩家而言"没变"远好于"没了"。
     */
    public static void convert(ServerLevel level, Mob source, EntityType<?> targetType) {
        CompoundTag tag = buildConversionTag(source);
        Vec3 pos = source.position();
        float yRot = source.getYRot();
        float xRot = source.getXRot();

        // 先建：建不出来就什么都不做，源实体原样留着。
        Entity target = targetType.create(level);
        if (target == null) {
            return;
        }

        // 再把继承来的状态 load 进去。这里可能抛（目标实体对这个 NBT 不满意），
        // 所以包一层并丢弃半成品——此时源实体仍然完好。
        try {
            target.load(tag);
        } catch (Exception failure) {
            target.discard();
            FocalDecay.LOGGER.warn("[focal_decay] entity conversion skipped:"
                            + " loading preserved NBT into {} failed: {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(targetType), failure.toString());
            return;
        }

        target.moveTo(pos.x, pos.y, pos.z, yRot, xRot);
        // 到这里新实体已经准备好了，才移除旧的。
        source.discard();
        level.addFreshEntity(target);
    }
}
