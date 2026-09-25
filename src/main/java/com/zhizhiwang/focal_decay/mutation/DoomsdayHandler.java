package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import com.zhizhiwang.focal_decay.mutation.pool.MutationIndexes;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /**
     * 单个实体本周期的突变骰子种子（<b>纯函数</b>，供自测直接调用）。
     * <p>
     * <b>为什么必须含实体身份</b>（2026-09-25 修，BACKLOG `P1-1`）：早先是
     * {@code mix64(worldSeed ^ pos ^ tick)}，而 {@code blockPosition()} 只是方块坐标。
     * 同一 tick、同一方格里的多个实体（牧场里挤在一起的牛羊、刷怪塔里堆叠的怪）
     * 于是拿到<b>完全相同的随机序列</b>——是否突变、突变成什么全都一样，
     * 表现为"一群牛同时变成同一只僵尸"。这在实体密集处非常显眼，而且明显不像自然现象。
     * <p>
     * 三个分量各自先雪崩再异或：坐标低位、UUID 低位、tick 低位是会互相纠缠的
     * （这个教训在方块种子那边付过一次学费，见 `PROGRESS` 的纵向随机性修复）。
     */
    public static long mutationSeed(long worldSeed, BlockPos pos, long tick, UUID entityId) {
        long identity = entityId == null ? 0L : entityId.getLeastSignificantBits();
        return MutationHelper.mix64(MutationHelper.mix64(pos.asLong())
                ^ MutationHelper.mix64(identity)
                ^ MutationHelper.mix64(worldSeed ^ tick));
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
        // 掉落物突变没有"形态类"可言（物品没有几何），所以取大池的跨形态扁平视图；
        // 但必须用<b>物品池</b>而不是方块池——不是每个方块都有对应物品，
        // asItem() 返回 AIR 时会产出空栈，而空栈物品实体下一 tick 就被丢弃（丢件）。见 MutationIndex#itemPool。
        Item[] itemTargets = MutationIndexes.get(level.dimension()).itemPool();
        if (entityPool.isEmpty() && itemTargets.length == 0) {
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
            long seed = mutationSeed(worldSeed, entity.blockPosition(), tick, entity.getUUID());
            RandomSource random = RandomSource.create(seed);
            if (random.nextDouble() >= chance) {
                continue;
            }

            if (entity instanceof ItemEntity itemEntity) {
                if (itemTargets.length > 0) {
                    Item target = itemTargets[random.nextInt(itemTargets.length)];
                    ItemStack stack = itemEntity.getItem();
                    // transmuteCopy 保留组件（附魔/命名/耐久/容器内容），只换物品类型；
                    // 原来用 new ItemStack(item, count) 会把组件全部丢掉。
                    // 计数原样保留（这是既定取向，不是本次修的缺陷）。
                    itemEntity.setItem(stack.transmuteCopy(target, stack.getCount()));
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

    /**
     * 实体突变种子的自测（{@code /focaldecay mutation selftest} 的 {@code [entity]} 一行）。
     * <p>
     * 钉的是 BACKLOG `P1-1`：种子必须含<b>实体身份</b>，否则同一 tick、同一方格里的多个实体
     * 会拿到完全相同的随机序列（"一群牛同时变成同一只僵尸"）。
     * <p>
     * 断言用<b>采样分布</b>而不是"两个 UUID 给出不同种子"——后者对单次比较来说是概率性的，
     * 写成断言等于是碰运气。这里在同一个位置、同一个 tick 上取 512 个不同 UUID，
     * 要求几乎全部互不相同；旧实现（不含身份）在这个断言下会是 1 种，直接 FAIL。
     */
    public static List<String> selfTest(long worldSeed) {
        List<String> out = new ArrayList<>();
        BlockPos pos = new BlockPos(64, 70, -32);
        long tick = 12345L;
        final int samples = 512;

        java.util.Set<Long> seeds = new java.util.HashSet<>();
        for (int i = 0; i < samples; i++) {
            UUID id = new UUID(0x5EED_0000_0000_0000L + i, 0xABCD_0000_0000_0000L + i);
            seeds.add(mutationSeed(worldSeed, pos, tick, id));
        }
        // 旧实现同一位置同一 tick 只可能给出 1 个种子；留一点余量以容忍理论碰撞。
        boolean distinct = seeds.size() >= samples - 4;
        out.add("[entity] same position+tick, " + samples + " different entities -> distinct seeds: "
                + (distinct ? "PASS" : "FAIL (" + seeds.size() + " distinct -"
                + " entities sharing a block would mutate identically)"));
        return out;
    }

}
