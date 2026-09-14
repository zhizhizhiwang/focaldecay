package com.zhizhiwang.focal_decay.client.particle;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 观测者核心的环绕火花。
 * <p>
 * 行为：从出生点飞到"出生点 + 位移"，全程无重力、无碰撞；用缓出曲线（起步快、末段慢），
 * 看起来是"漂"而不是匀速滑动。
 * <p>
 * 存活时长来自 config，所以在同一个粒子上就能把密度 / 轨道半径 / 存在时长全做成可调项——
 * 这正是不能用原版 enchant 粒子的原因。
 */
public class ObserverSparkParticle extends TextureSheetParticle {

    private final double startX;
    private final double startY;
    private final double startZ;
    private final double travelX;
    private final double travelY;
    private final double travelZ;

    private ObserverSparkParticle(ClientLevel level, double x, double y, double z,
                                  double travelX, double travelY, double travelZ, SpriteSet sprites) {
        super(level, x, y, z);
        this.startX = x;
        this.startY = y;
        this.startZ = z;
        this.travelX = travelX;
        this.travelY = travelY;
        this.travelZ = travelZ;
        this.hasPhysics = false;
        this.quadSize = 0.09F * (this.random.nextFloat() * 0.5F + 0.4F);

        // 加 0~50% 随机抖动，避免同一批粒子整齐划一地同时消失
        int baseLifetime = FocalDecayConfig.OBSERVER_CORE_PARTICLE_LIFETIME.get();
        this.lifetime = Math.max(1, baseLifetime + this.random.nextInt(Math.max(1, baseLifetime / 2 + 1)));

        float shade = this.random.nextFloat() * 0.6F + 0.4F;
        this.rCol = 0.9F * shade;
        this.gCol = 0.9F * shade;
        this.bCol = shade;
        this.pickSprite(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        float t = (float) this.age / (float) this.lifetime;
        float ease = 1.0F - (1.0F - t) * (1.0F - t);
        this.x = this.startX + this.travelX * ease;
        this.y = this.startY + this.travelY * ease;
        this.z = this.startZ + this.travelZ * ease;
    }

    /** 由 {@code RegisterParticleProvidersEvent#registerSpriteSet} 注册，贴图集取自 observer_spark.json。 */
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public ObserverSparkParticle createParticle(SimpleParticleType type, ClientLevel level,
                                                    double x, double y, double z,
                                                    double travelX, double travelY, double travelZ) {
            return new ObserverSparkParticle(level, x, y, z, travelX, travelY, travelZ, this.sprites);
        }
    }
}