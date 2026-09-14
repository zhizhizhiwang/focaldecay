package com.zhizhiwang.focal_decay.particle;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 粒子类型注册。
 * <p>
 * 为什么不用原版的 {@code minecraft:enchant}：它的存在时长（30~40 tick）和运动曲线都硬编码在
 * {@code FlyTowardsPositionParticle} 里，没法定制。想要"寿命 / 轨道半径 / 密度"全部可配，
 * 就得自己注册一个同款观感的粒子。
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, FocalDecay.MODID);

    /** 观测者核心四周环绕的火花。贴图直接复用原版附魔台的 sga_* 粒子。 */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> OBSERVER_SPARK =
            PARTICLE_TYPES.register("observer_spark", () -> new SimpleParticleType(false));

    private ModParticles() {
    }
}