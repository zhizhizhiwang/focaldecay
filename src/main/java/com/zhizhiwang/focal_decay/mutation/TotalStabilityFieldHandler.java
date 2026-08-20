package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.data.ObserverModelData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 完全稳定锚的特殊视觉（里程碑 6）：范围内周期性生成旋转光环粒子 + 锚上方漂浮粒子。
 * 仅对已激活完全稳定模型（TYPE_TOTAL）生效；未激活模型无数据、不产生效果。
 */
public final class TotalStabilityFieldHandler {
    private static final int AURA_INTERVAL = 30;

    private TotalStabilityFieldHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.players().isEmpty() || level.getGameTime() % AURA_INTERVAL != 0) {
                continue;
            }
            for (MutationPoolManager.PrototypeEffect effect : MutationPoolManager.get(level).getPrototypeEffects()) {
                if (ObserverModelData.TYPE_TOTAL.equals(effect.data().type())) {
                    spawnAura(level, effect.center(), level.getGameTime());
                }
            }
        }
    }

    private static void spawnAura(ServerLevel level, BlockPos center, long gameTime) {
        for (int i = 0; i < 4; i++) {
            level.sendParticles(ParticleTypes.END_ROD,
                    center.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 2,
                    center.getY() + 1.5 + level.random.nextDouble() * 2,
                    center.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 2,
                    1, 0, 0.1, 0, 0);
        }
        ring(level, center, 6, 12, gameTime);
        ring(level, center, 12, 10, gameTime);
        ring(level, center, 18, 8, gameTime);
        level.sendParticles(ParticleTypes.PORTAL,
                center.getX() + 0.5, center.getY() + 2.0, center.getZ() + 0.5,
                6, 2, 1, 2, 0.1);
    }

    private static void ring(ServerLevel level, BlockPos center, int radius, int count, long gameTime) {
        double angle = gameTime * 0.02;
        for (int i = 0; i < count; i++) {
            double a = angle + i * Math.PI * 2.0 / count;
            level.sendParticles(ParticleTypes.END_ROD,
                    center.getX() + 0.5 + Math.cos(a) * radius,
                    center.getY() + 2.0,
                    center.getZ() + 0.5 + Math.sin(a) * radius,
                    1, 0, 0.1, 0, 0);
        }
    }
}
