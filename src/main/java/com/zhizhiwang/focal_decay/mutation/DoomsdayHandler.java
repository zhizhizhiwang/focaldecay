package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 末日阶段系统（设计大纲 §6 / PROGRESS 第 8 项）：
 *  - 驱动 FocalDecayWorldData 天数累计；
 *  - 每阶段周期对实体（Mob）与掉落物（ItemEntity）执行确定性转换。
 */
public final class DoomsdayHandler {
    /**
     * 实体 / 天气突变的节拍累加器（单位：失焦时钟的刻）。
     * <p>
     * 用累加器而不是 {@code serverTick - last >= interval}，是因为倍率可以是分数（0.5 倍速）：
     * 写成 {@code interval / speed} 会被整除截断，0.5 倍和 1 倍就没区别了。
     * {@code speed = 1} 时每 tick 加 1、满 interval 触发并清零，与旧实现等价。
     * <p>
     * 为什么受调试倍率影响：{@code /focaldecay period speed} 的语义是"失焦进程整体的流速"，
     * 实体和天气同属这个过程（用户选择），所以一起吃倍率。
     */
    private static double entityClockAccumulator;
    private static double weatherClockAccumulator;

    private DoomsdayHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        FocalDecayWorldData worldData = FocalDecayWorldData.get(server);
        worldData.tick(server);
        double clockSpeed = worldData.getClockSpeed();
        if (worldData.isObserverOnline()) {
            // 失焦终止：跳过实体突变。累加器归零，重新上线时不会立刻补一发。
            entityClockAccumulator = 0.0;
            weatherClockAccumulator = 0.0;
            return;
        }

        long serverTick = server.getTickCount();
        int stage = MutationHelper.currentStage(worldData.getDays());
        long interval = MutationHelper.intervalForStage(stage);

        // 倍率为 0（冻结）时累加器不动 → 实体/天气也停；负倍率下夹在 0，
        // 因为实体转换是真实的世界改动，没有"倒带"可言（能倒带的只有方块失焦外观）。
        entityClockAccumulator = advanceClock(entityClockAccumulator, clockSpeed, interval);
        if (entityClockAccumulator >= interval) {
            entityClockAccumulator = 0.0;
            for (ServerLevel level : server.getAllLevels()) {
                mutateEntities(level, serverTick, stage);
            }
        }
        weatherClockAccumulator = advanceClock(weatherClockAccumulator, clockSpeed, interval);
        if (weatherClockAccumulator >= interval) {
            weatherClockAccumulator = 0.0;
            for (ServerLevel level : server.getAllLevels()) {
                mutateWeather(level, serverTick, stage);
            }
        }
    }

    /** 推进节拍累加器：夹在 [0, interval]，因此正负倍率都不会把它推到荒唐的位置。 */
    private static double advanceClock(double accumulator, double clockSpeed, long interval) {
        return Math.min(interval, Math.max(0.0, accumulator + clockSpeed));
    }

    /**
     * 天气突变（2026-08-21）：按阶段概率掷确定性骰子，命中把主世界天气随机转为
     * 与当前不同的状态（晴 / 雨 / 雷暴），持续一段随机时长——与实体突变同周期、同风格。
     */
    private static void mutateWeather(ServerLevel level, long tick, int stage) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return;
        }
        double chance = switch (stage) {
            case 2 -> FocalDecayConfig.WEATHER_MUTATION_CHANCE_STAGE2.get();
            case 3 -> FocalDecayConfig.WEATHER_MUTATION_CHANCE_STAGE3.get();
            default -> FocalDecayConfig.WEATHER_MUTATION_CHANCE_STAGE1.get();
        };
        if (chance <= 0.0) {
            return;
        }
        long seed = MutationHelper.mix64(level.getSeed() ^ tick);
        RandomSource random = RandomSource.create(seed);
        if (random.nextDouble() >= chance) {
            return;
        }
        int current = level.isThundering() ? 2 : (level.isRaining() ? 1 : 0);
        int target;
        do {
            target = random.nextInt(3);
        } while (target == current);
        int duration = 1200 + random.nextInt(6000); // 60 ~ 360 秒
        switch (target) {
            case 1 -> level.setWeatherParameters(0, duration, true, false);
            case 2 -> level.setWeatherParameters(0, duration, true, true);
            default -> level.setWeatherParameters(duration, 0, false, false);
        }
    }

    /** 每周期对每个实体掷确定性骰子，命中的生物/掉落物转换为池内目标。 */
    private static void mutateEntities(ServerLevel level, long tick, int stage) {
        double chance = switch (stage) {
            case 2 -> FocalDecayConfig.ENTITY_MUTATION_CHANCE_STAGE2.get();
            case 3 -> FocalDecayConfig.ENTITY_MUTATION_CHANCE_STAGE3.get();
            default -> 0.0;
        };
        if (chance <= 0.0) {
            return;
        }

        List<EntityType<?>> entityPool = resolveEntityPool(level, stage);
        // 掉落物突变没有"形态类"可言（物品没有几何），所以直接取大池的跨形态扁平视图。
        Block[] blockPool = MutationIndexes.get(level.dimension()).wild().flat();
        if (entityPool.isEmpty() && blockPool.length == 0) {
            return;
        }

        long worldSeed = level.getSeed();
        // 拷贝成快照再遍历：level.getEntities().getAll() 是活动视图，循环内 discard/addFreshEntity
        // 会让列表出现 null 墓碑；快照也避免并发修改异常。
        List<Entity> snapshot = new ArrayList<>();
        level.getEntities().getAll().forEach(snapshot::add);
        for (Entity entity : snapshot) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            long seed = MutationHelper.mix64(worldSeed ^ entity.blockPosition().asLong() ^ tick);
            RandomSource random = RandomSource.create(seed);
            if (random.nextDouble() >= chance) {
                continue;
            }

            if (entity instanceof ItemEntity itemEntity) {
                if (blockPool.length > 0) {
                    Block block = blockPool[random.nextInt(blockPool.length)];
                    ItemStack stack = itemEntity.getItem();
                    itemEntity.setItem(new ItemStack(block.asItem(), stack.getCount()));
                }
            } else if (entity instanceof Mob mob && !(entity instanceof Player) && !entityPool.isEmpty()) {
                if (isEntityProtected(level, mob)) {
                    continue;
                }
                EntityType<?> targetType = entityPool.get(random.nextInt(entityPool.size()));
                if (targetType != entity.getType()) {
                    EntityMutation.convert(level, mob, targetType);
                }
            }
        }
    }

    /** 实体是否处于某原型机效果的"生物稳定"范围内（生物稳定/完全稳定全部，语义锁定命中训练实体）。 */
    private static boolean isEntityProtected(ServerLevel level, Entity entity) {
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        for (MutationPoolManager.PrototypeEffect effect : MutationPoolManager.get(level).getPrototypeEffects()) {
            if (Math.max(Math.abs((long) entity.getX() - effect.center().getX()),
                    Math.max(Math.abs((long) entity.getY() - effect.center().getY()),
                            Math.abs((long) entity.getZ() - effect.center().getZ()))) > effect.radius()) {
                continue;
            }
            String type = effect.data().type();
            if (ObserverModelData.TYPE_TOTAL.equals(type)) {
                return true;
            }
            if (ObserverModelData.TYPE_CANDIDATE.equals(type) && effect.data().candidateComplete()) {
                return true; // 已完成候选 = 完全稳定
            }
            if (ObserverModelData.TYPE_BIO.equals(type)) {
                return FocalDecayConfig.BIO_STABILIZE_ENTITIES.get() && effect.data().bioEnergy() > 0;
            }
            if (ObserverModelData.TYPE_SEMANTIC_LOCK.equals(type)
                    && effect.data().trainedEntities().contains(entityId)) {
                return true;
            }
        }
        return false;
    }

    /** 阶段实体池：阶段1被动，阶段2加入中立，阶段3加入敌对。 */
    private static List<EntityType<?>> resolveEntityPool(ServerLevel level, int stage) {
        List<EntityType<?>> pool = new ArrayList<>();
        addFromTag(level, pool, ModTags.EntityTypes.ENTITY_MUTATION_POOL_PASSIVE);
        if (stage >= 2) {
            addFromTag(level, pool, ModTags.EntityTypes.ENTITY_MUTATION_POOL_NEUTRAL);
        }
        if (stage >= 3) {
            addFromTag(level, pool, ModTags.EntityTypes.ENTITY_MUTATION_POOL_HOSTILE);
        }
        return pool;
    }

    private static void addFromTag(ServerLevel level, List<EntityType<?>> pool, TagKey<EntityType<?>> tag) {
        level.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE)
                .get(tag)
                .ifPresent(holders -> holders.forEach(holder -> pool.add(holder.value())));
    }

}
