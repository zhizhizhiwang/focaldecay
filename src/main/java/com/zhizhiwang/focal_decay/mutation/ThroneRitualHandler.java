package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.AnchorPrototypeBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import com.zhizhiwang.focal_decay.network.ThroneRitualPacket;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * 末地王座仪式（设计大纲 §3.5.1）：
 *  - 右键王座基座触发：需携带稳定锚原型机（物品或已放置）+ 完全稳定模型（未激活）；
 *  - 期间按配置波次生成强敌，玩家离开半径按配置暂停或失败；
 *  - 完成：消耗未激活模型，范围内原型机插槽升级为已激活完全稳定模型（否则给予独立物品），
 *    广播 ThroneRitualPacket、粒子与音效；
 *  - 常驻粒子：王座周围周期性漂浮末地棒/传送门粒子。
 */
public final class ThroneRitualHandler {
    private static final int TICKS_PER_SECOND = 20;
    private static final int AMBIENT_PARTICLE_INTERVAL = 40;

    private ThroneRitualHandler() {
    }

    // ------------------------------------------------------------------
    // 触发
    // ------------------------------------------------------------------
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.END) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockPos throne = ThroneStructure.thronePos(level.getSeed());
        if (!isThroneBase(pos, throne)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        ThroneRitualData data = ThroneRitualData.get(level);
        if (data.isActive()) {
            player.displayClientMessage(Component.translatable("message.focal_decay.ritual_active"), true);
            return;
        }
        if (!hasUnactivatedModel(player)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.ritual_need_model"), true);
            return;
        }
        int radius = FocalDecayConfig.THRONE_RITUAL_RADIUS.get();
        if (!hasPrototype(player, level, throne, radius)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.ritual_need_prototype"), true);
            return;
        }

        int totalTicks = FocalDecayConfig.THRONE_RITUAL_SECONDS.get() * TICKS_PER_SECOND;
        int waveInterval = FocalDecayConfig.THRONE_RITUAL_WAVE_INTERVAL_SECONDS.get() * TICKS_PER_SECOND;
        data.start(throne.asLong(), player.getUUID(), totalTicks, waveInterval);
        event.setCanceled(true);

        level.sendParticles(ParticleTypes.PORTAL,
                throne.getX() + 0.5, throne.getY() + 2.0, throne.getZ() + 0.5,
                200, 8, 4, 8, 0.5);
        level.sendParticles(ParticleTypes.END_ROD,
                throne.getX() + 0.5, throne.getY() + 6.0, throne.getZ() + 0.5,
                120, 10, 6, 10, 0.4);
        level.playSound(null, throne, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 1.0F, 1.0F);
        level.playSound(null, throne, SoundEvents.CONDUIT_ACTIVATE, SoundSource.AMBIENT, 1.0F, 1.0F);
        ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                ThroneRitualPacket.STATE_STARTED, data.remainingTicks(), data.totalTicks(), data.wave()));
    }

    // ------------------------------------------------------------------
    // 计时 / 波次 / 完成
    // ------------------------------------------------------------------
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension() != Level.END) {
                continue;
            }
            if (!level.players().isEmpty()
                    && level.getGameTime() % AMBIENT_PARTICLE_INTERVAL == 0) {
                BlockPos throne = ThroneStructure.thronePos(level.getSeed());
                if (level.isLoaded(throne)) {
                    spawnAmbientParticles(level, throne);
                }
            }
            ThroneRitualData data = ThroneRitualData.get(level);
            if (data.isActive()) {
                tickRitual(level, data);
            }
        }
    }

    private static void tickRitual(ServerLevel level, ThroneRitualData data) {
        ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(data.playerId());
        BlockPos throne = BlockPos.of(data.thronePos());
        if (player == null || !player.isAlive()) {
            fail(level, data);
            return;
        }
        int radius = FocalDecayConfig.THRONE_RITUAL_RADIUS.get();
        if (player.blockPosition().distSqr(throne) > (double) radius * radius) {
            if (FocalDecayConfig.THRONE_RITUAL_PAUSE_ON_LEAVE.get()) {
                data.pause();
                ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                        ThroneRitualPacket.STATE_PAUSED, data.remainingTicks(), data.totalTicks(), data.wave()));
            } else {
                fail(level, data);
            }
            return;
        }

        data.setRemainingTicks(data.remainingTicks() - 1);
        if (data.remainingTicks() <= 0) {
            complete(level, data);
            return;
        }
        data.setNextWaveTicks(data.nextWaveTicks() - 1);
        if (data.nextWaveTicks() <= 0) {
            data.setWave(data.wave() + 1);
            data.setNextWaveTicks(FocalDecayConfig.THRONE_RITUAL_WAVE_INTERVAL_SECONDS.get() * TICKS_PER_SECOND);
            spawnWave(level, throne, data.wave());
            level.sendParticles(ParticleTypes.DRAGON_BREATH,
                    throne.getX() + 0.5, throne.getY() + 3.0, throne.getZ() + 0.5,
                    60, 8, 3, 8, 0.2);
            level.playSound(null, throne, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.AMBIENT, 1.0F, 1.0F);
            ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                    ThroneRitualPacket.STATE_WAVE, data.remainingTicks(), data.totalTicks(), data.wave()));
        }
        if (level.getGameTime() % TICKS_PER_SECOND == 0) {
            level.sendParticles(ParticleTypes.PORTAL,
                    throne.getX() + 0.5, throne.getY() + 4.0, throne.getZ() + 0.5,
                    40, 8, 4, 8, 0.3);
            level.sendParticles(ParticleTypes.END_ROD,
                    throne.getX() + 0.5, throne.getY() + 5.0, throne.getZ() + 0.5,
                    12, 8, 4, 8, 0.15);
            ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                    ThroneRitualPacket.STATE_PROGRESS, data.remainingTicks(), data.totalTicks(), data.wave()));
        }
        data.setDirty();
    }

    private static void complete(ServerLevel level, ThroneRitualData data) {
        ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(data.playerId());
        BlockPos throne = BlockPos.of(data.thronePos());

        ItemStack activated = new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get());
        ObserverModelItem.setData(activated, new ObserverModelData(
                ObserverModelData.TYPE_TOTAL, List.of(), List.of(), 1.0, 0, true));

        // 只升级"槽内本来就是未激活完全稳定模型"的原型机；其他情况不动插槽，
        // 激活模型交还玩家背包（避免覆盖原型机里原有的模型）。
        AnchorPrototypeBlockEntity prototype =
                findPrototype(level, throne, FocalDecayConfig.THRONE_RITUAL_RADIUS.get());
        boolean upgradedInPlace = false;
        if (prototype != null && prototype.getModelStack().is(ModItems.TOTAL_STABILITY_MODEL.get())) {
            prototype.setItem(0, activated);
            upgradedInPlace = true;
        }
        if (player != null) {
            removeOne(player, ModItems.TOTAL_STABILITY_MODEL.get());
        }
        if (!upgradedInPlace && player != null) {
            if (!player.getInventory().add(activated)) {
                level.addFreshEntity(new ItemEntity(level,
                        throne.getX() + 0.5, throne.getY() + 2.0, throne.getZ() + 0.5, activated));
            }
        }

        level.sendParticles(ParticleTypes.END_ROD,
                throne.getX() + 0.5, throne.getY() + 4.0, throne.getZ() + 0.5,
                400, 10, 6, 10, 0.3);
        level.sendParticles(ParticleTypes.PORTAL,
                throne.getX() + 0.5, throne.getY() + 6.0, throne.getZ() + 0.5,
                300, 12, 8, 12, 0.4);
        level.playSound(null, throne, SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 1.0F, 1.0F);
        level.playSound(null, throne, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 1.0F, 1.0F);
        ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                ThroneRitualPacket.STATE_COMPLETE, 0, 0, 0));
        data.stop();
    }

    private static void fail(ServerLevel level, ThroneRitualData data) {
        ModNetwork.sendToAllPlayers(new ThroneRitualPacket(
                ThroneRitualPacket.STATE_FAILED, 0, 0, 0));
        data.stop();
    }

    private static void spawnWave(ServerLevel level, BlockPos throne, int wave) {
        List<? extends String> ids = FocalDecayConfig.THRONE_RITUAL_WAVE_ENTITIES.get();
        int size = FocalDecayConfig.THRONE_RITUAL_WAVE_SIZE.get();
        if (ids.isEmpty() || size <= 0) {
            return;
        }
        RandomSource random = level.getRandom();
        for (int i = 0; i < size; i++) {
            String id = ids.get(random.nextInt(ids.size()));
            try {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
                Entity entity = type == null ? null : type.create(level);
                if (entity == null) {
                    continue;
                }
                int dx = random.nextInt(13) - 6;
                int dz = random.nextInt(13) - 6;
                entity.setPos(throne.getX() + dx + 0.5, throne.getY() + 2.0, throne.getZ() + dz + 0.5);
                level.addFreshEntity(entity);
            } catch (Exception ignored) {
                // 非法实体 ID 忽略
            }
        }
    }

    // ------------------------------------------------------------------
    // 条件与辅助
    // ------------------------------------------------------------------
    private static void spawnAmbientParticles(ServerLevel level, BlockPos throne) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < 6; i++) {
            double x = throne.getX() + (random.nextDouble() - 0.5) * 14;
            double z = throne.getZ() + (random.nextDouble() - 0.5) * 14;
            double y = throne.getY() + 2.0 + random.nextDouble() * 16;
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0.05, 0, 0);
        }
        level.sendParticles(ParticleTypes.PORTAL,
                throne.getX() + 0.5, throne.getY() + 3.0, throne.getZ() + 0.5,
                8, 6, 3, 6, 0.1);
    }

    private static boolean isThroneBase(BlockPos pos, BlockPos throne) {
        return Math.abs(pos.getX() - throne.getX()) <= 2
                && Math.abs(pos.getZ() - throne.getZ()) <= 2
                && pos.getY() >= throne.getY() - 1 && pos.getY() <= throne.getY() + 1;
    }

    private static boolean hasUnactivatedModel(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.TOTAL_STABILITY_MODEL.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrototype(ServerPlayer player, ServerLevel level, BlockPos throne, int radius) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModBlocks.ANCHOR_PROTOTYPE.get().asItem())) {
                return true;
            }
        }
        return findPrototype(level, throne, radius) != null;
    }

    private static AnchorPrototypeBlockEntity findPrototype(ServerLevel level, BlockPos throne, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(
                throne.offset(-radius, -8, -radius), throne.offset(radius, 8, radius))) {
            if (level.getBlockState(pos).is(ModBlocks.ANCHOR_PROTOTYPE.get())
                    && level.getBlockEntity(pos) instanceof AnchorPrototypeBlockEntity be) {
                return be;
            }
        }
        return null;
    }

    private static void removeOne(ServerPlayer player, Item item) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                stack.shrink(1);
                return;
            }
        }
    }
}
