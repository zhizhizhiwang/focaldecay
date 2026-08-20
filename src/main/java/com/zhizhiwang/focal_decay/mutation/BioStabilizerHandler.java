package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * 生物稳定模型（设计大纲 §6.5 / PROGRESS 里程碑 4）：
 *  - 每 20 tick 结算一次：模型消耗 bioEnergy 维持效果，阶段 3 消耗双倍（可配置）；
 *  - 能量未满时，范围内（非玩家）生物每只损失 1 点生命值，按换算率补充 bioEnergy（不高于容量）；
 *  - bioEnergy 耗尽后效果失效：方块不再受保护、实体不再跳过突变（由 isProtected/isEntityProtected 判定）；
 *  - 能量"活跃↔耗尽"翻转时向维度广播区域数据，客户端即时更新预览与中键选取。
 */
public final class BioStabilizerHandler {
    private static final int TICKS_PER_SECOND = 20;

    private BioStabilizerHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getGameTime() % TICKS_PER_SECOND == 0) {
                tickLevel(level);
            }
        }
    }

    private static void tickLevel(ServerLevel level) {
        MutationPoolManager manager = MutationPoolManager.get(level);
        // 快照遍历：本方法内不会增删 effect，但避免后续修改导致的并发修改风险。
        List<MutationPoolManager.PrototypeEffect> effects = List.copyOf(manager.getPrototypeEffects());
        for (MutationPoolManager.PrototypeEffect effect : effects) {
            if (!ObserverModelData.TYPE_BIO.equals(effect.data().type())) {
                continue;
            }
            if (!(level.getBlockEntity(effect.center()) instanceof AnchorPrototypeBlockEntity be)) {
                continue;
            }
            ItemStack model = be.getModelStack();
            ObserverModelData data = ObserverModelItem.getData(model);
            if (data == null || !ObserverModelData.TYPE_BIO.equals(data.type())) {
                continue;
            }
            tickPrototype(level, be, model, data, effect.radius());
        }
    }

    private static void tickPrototype(ServerLevel level, AnchorPrototypeBlockEntity be, ItemStack model,
                                      ObserverModelData data, int radius) {
        int capacity = Math.max(0, FocalDecayConfig.BIO_ENERGY_CAPACITY.get());
        int energy = Math.max(0, Math.min(capacity, data.bioEnergy()));
        boolean wasActive = energy > 0;

        // 1) 维持效果消耗能量（阶段 3 双倍）
        int drain = Math.max(0, FocalDecayConfig.BIO_DRAIN_PER_SECOND.get());
        long days = FocalDecayWorldData.get(level.getServer()).getDays();
        if (MutationHelper.currentStage(days) >= 3 && FocalDecayConfig.BIO_STAGE3_DOUBLE_DRAIN.get()) {
            drain *= 2;
        }
        energy = Math.max(0, energy - drain);

        // 2) 能量未满时从范围内生物抽取生命值补充
        if (energy < capacity) {
            int conversion = Math.max(0, FocalDecayConfig.BIO_CONVERSION_PER_HP.get());
            if (conversion > 0) {
                BlockPos center = be.getBlockPos();
                int r = Math.max(1, radius);
                AABB box = new AABB(
                        center.getX() - r, center.getY() - r, center.getZ() - r,
                        center.getX() + r + 1.0, center.getY() + r + 1.0, center.getZ() + r + 1.0);
                for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box,
                        e -> e.isAlive() && !(e instanceof Player))) {
                    living.hurt(level.damageSources().magic(), 1.0F);
                    energy = Math.min(capacity, energy + conversion);
                    if (energy >= capacity) {
                        break;
                    }
                }
            }
        }

        if (energy == data.bioEnergy()) {
            return; // 无变化
        }
        ObserverModelItem.setData(model, new ObserverModelData(
                data.type(), data.trainedTargets(), data.trainedEntities(),
                data.stabilityStrength(), energy, data.totalStability()));
        be.setChanged();

        MutationPoolManager manager = MutationPoolManager.get(level);
        manager.updatePrototypeEffect(be.getBlockPos(), model);
        if (wasActive != (energy > 0)) {
            ModNetwork.sendRegionDataToDimension(level);
        }
    }
}
