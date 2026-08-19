package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.tags.ModTags;
import net.minecraft.core.registries.Registries;
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
    // 服务器 tick 从 0 开始。不要用 Long.MIN_VALUE 做"未初始化"标记：
    // serverTick - MIN_VALUE 会溢出成负数，周期判断恒为假，实体突变永远不会执行。
    private static long lastEntityMutationTick = 0;

    private DoomsdayHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        FocalDecayWorldData worldData = FocalDecayWorldData.get(server);
        worldData.tick(server);

        long serverTick = server.getTickCount();
        int stage = MutationHelper.currentStage(worldData.getDays());
        long interval = MutationHelper.intervalForStage(stage);
        if (serverTick - lastEntityMutationTick >= interval) {
            lastEntityMutationTick = serverTick;
            for (ServerLevel level : server.getAllLevels()) {
                mutateEntities(level, serverTick, stage);
            }
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
        List<Block> blockPool = MutationPoolManager.get(level).getGlobalPool().snapshot();
        if (entityPool.isEmpty() && blockPool.isEmpty()) {
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
                if (!blockPool.isEmpty()) {
                    Block block = blockPool.get(random.nextInt(blockPool.size()));
                    ItemStack stack = itemEntity.getItem();
                    itemEntity.setItem(new ItemStack(block.asItem(), stack.getCount()));
                }
            } else if (entity instanceof Mob mob && !(entity instanceof Player) && !entityPool.isEmpty()) {
                EntityType<?> targetType = entityPool.get(random.nextInt(entityPool.size()));
                if (targetType != entity.getType()) {
                    EntityMutation.convert(level, mob, targetType);
                }
            }
        }
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
