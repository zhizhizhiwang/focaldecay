package com.zhizhiwang.focal_decay.block.entity;

import com.zhizhiwang.focal_decay.block.ObserverCoreBlock;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 观测者核心的转子动画状态。
 * <p>
 * 全部字段都是客户端瞬态：不写 NBT、不走网络，ticker 也只在客户端注册——与原版附魔台同一套做法。
 * 未激活时转子静止贴在底座上；激活后升起 {@link #LIFT_HEIGHT} 并做"先快后匀速"的俯视顺时针旋转，
 * 再叠加上下浮动。
 * <p>
 * <b>升起与浮动的公式都提成了 {@code static} 纯函数</b>，因为取用它们的不止渲染一条路：
 * {@code Block#animateTick} 撒粒子时也需要同样的升起/浮动偏移，而那一刻方块实体可能还没被 tick 过
 * （客户端刚同步到区块时它就是默认值）。以前粒子直接读 {@code core.lift} / {@code core.bob} 字段，
 * 读到的是"半路的中间态"，于是转子已经升起来了、粒子却还从底座高度往外撒。
 * 渲染与粒子现在共用同一组公式，无论方块实体有没有 tick 过，结果都一致。
 */
public class ObserverCoreBlockEntity extends BlockEntity {

    // ------------------------------------------------------------------
    // 几何与运动常量（渲染包围盒也由它们推导，别再手写字面量）
    // ------------------------------------------------------------------

    /** 转子静止时贴底，激活后升起的高度（格）。取自原 observer_core_active 模型的 4px 高度差。 */
    public static final float LIFT_HEIGHT = 0.25F;
    /** 转子几何中心相对方块底部的高度（模型里转子 y 2..8 px，中心 5px = 5/16 格）。 */
    public static final double ROTOR_CENTER_Y = 5.0D / 16.0D;
    /** 转子截面的半宽（模型 x/z 为 5..11，半宽 3px）。 */
    public static final double ROTOR_HALF_SIZE = 3.0D / 16.0D;
    /**
     * 转子绕中心旋转时，在轴对齐方向上的最大外扩半径。
     * <p>
     * 直觉上"转 45° 最宽"，但那是半对角线；真正决定轴对齐外接盒的是半宽乘以
     * {@code |cos| + |sin|}，而它在 90° 的整数倍处取极值 1——也就是"摆正"的时候。
     * 所以旋转不会把外接半径撑得比 {@link #ROTOR_HALF_SIZE} 更大。
     */
    public static final double ROTOR_SWEEP_RADIUS = ROTOR_HALF_SIZE;

    /** 升起/落回的缓动系数，约 0.5 秒（10 tick）走完。 */
    private static final float LIFT_EASE = 0.25F;
    /** 缓停的指数衰减系数。 */
    private static final float STOP_EASE = 0.1F;
    /** 转速低于此值直接归零，避免无限拖尾。 */
    private static final float SPIN_EPSILON = 0.01F;
    /** 升起进度逼近到这个程度就当作"完全升起"（1 - 0.75^21 < 0.002，约 1 秒）。 */
    private static final float EASE_SETTLED = 0.002F;
    /**
     * "完全升起"所需的时间（tick）：指数缓动逼近到 {@link #EASE_SETTLED} 以内。
     * 默认参数（{@code LIFT_EASE = 0.25}）下是 21 tick，约 1 秒——粒子环升到位的时间与它一致。
     */
    public static final int ACTIVATION_DELAY = (int) Math.ceil(Math.log(EASE_SETTLED) / Math.log(1.0F - LIFT_EASE));

    /** 当前转速（度/tick）。未激活时指数衰减到 0。 */
    public float spinSpeed;
    /** 自激活起的 tick 数，同时充当"完全升起之后又过了多少 tick"的计时器。 */
    public int spinTime;
    /** 累计旋转角（度），归一化到 [0, 360)。 */
    public float spin;
    public float oSpin;
    /** 升起进度 0~1。 */
    public float lift;
    public float oLift;
    /** 浮动相位（弧度）：以"完全升起的那一 tick"为原点推进，所以停止后相位冻住。 */
    public float bobPhase;
    /** 浮动偏移（格）。由 {@link #bobOffset} 按需算出，这里只留着方便调试查看。 */
    public float bob;
    public float oBob;

    public ObserverCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OBSERVER_CORE.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ObserverCoreBlockEntity core) {
        // 先存旧值，渲染时按 partialTick 插值；否则高速旋转会抖
        core.oSpin = core.spin;
        core.oLift = core.lift;
        core.oBob = core.bob;

        boolean powered = state.getValue(ObserverCoreBlock.POWERED);
        long gameTime = level.getGameTime();

        if (powered) {
            core.spinTime++;
            // 先快后匀速：峰值角速度按指数衰减到匀速
            float peak = FocalDecayConfig.OBSERVER_CORE_SPIN_PEAK_SPEED.get().floatValue();
            float steady = FocalDecayConfig.OBSERVER_CORE_SPIN_SPEED.get().floatValue();
            double tau = FocalDecayConfig.OBSERVER_CORE_SPIN_EASE_TICKS.get();
            core.spinSpeed = steady + (peak - steady) * (float) Math.exp(-core.spinTime / tau);
        } else {
            core.spinTime = 0;
            // 对称缓停：同样的时间常数衰减到 0
            core.spinSpeed -= core.spinSpeed * STOP_EASE;
            if (Math.abs(core.spinSpeed) < SPIN_EPSILON) {
                core.spinSpeed = 0.0F;
            }
        }

        core.spin += core.spinSpeed;
        if (core.spin >= 360.0F) {
            core.spin -= 360.0F;
        }

        // 升起与浮动都按游戏时间推导（见类注释），结果与"逐 tick 累加"在几个 tick 内就一致
        core.lift = liftFraction(gameTime, powered);
        core.bobPhase = bobPhaseFor(gameTime, powered, core.spinTime);
        core.bob = bobOffsetAt(core.lift, core.bobPhase);
    }

    // ------------------------------------------------------------------
    // 渲染与粒子共用（唯一入口）
    // ------------------------------------------------------------------

    /** 渲染用：跨 tick 插值后的旋转角；跨 360° 时走最短路径（rotLerp 内部按 wrapDegrees 处理）。 */
    public float spinAngle(float partialTick) {
        return Mth.rotLerp(partialTick, oSpin, spin);
    }

    /** 渲染用：插值后的升起进度。 */
    public float liftAmount(float partialTick) {
        return Mth.lerp(partialTick, oLift, lift);
    }

    /**
     * 渲染与粒子共用的浮动偏移（格），跨 tick 插值。
     * <p>
     * 由 {@code level} 现场推导，因此不依赖本方块实体"已经被 tick 过"——客户端刚同步到区块时
     * 也能算对，粒子不会从底座高度往外撒。
     */
    public float bobOffset(Level level, boolean powered, float partialTick) {
        long gameTime = level.getGameTime();
        return Mth.lerp(partialTick, oBob, bobOffsetAt(liftFraction(gameTime, powered),
                bobPhaseFor(gameTime, powered, spinTime)));
    }

    /**
     * 带计时器的相位：与 {@link #bobPhaseAt} 同一公式，但把 {@code spinTime} 也纳入原点。
     * <p>
     * 它是给方块实体自己的 {@link #tick} 用的——方块实体每 tick 都在推进，{@code spinTime}
     * 与游戏时间同步增长，所以相位与 {@link #bobPhaseAt} 的结果一致；
     * 而"没 tick 过"的调用方走 {@link #bobPhaseAt}，不会因为 {@code spinTime} 还是 0 而错位。
     */
    private static float bobPhaseFor(long gameTime, boolean powered, int spinTime) {
        if (!powered) {
            return 0.0F;
        }
        return phaseAfter(gameTime - activationTickOf(gameTime) - spinTime,
                FocalDecayConfig.OBSERVER_CORE_BOB_PERIOD_TICKS.get());
    }

    // ------------------------------------------------------------------
    // 纯函数形式（供没有方块实体上下文的调用方复用）
    // ------------------------------------------------------------------

    /** 升起进度：指数缓动 {@code 1 - (1 - LIFT_EASE)^ticks}。 */
    public static float liftFraction(long gameTime, boolean powered) {
        return liftFractionFrom(powered ? gameTime - activationTickOf(gameTime) : 0L);
    }

    /** 升起进度：由"已持续 tick 数"直接算出。 */
    public static float liftFractionFrom(long ticks) {
        return 1.0F - (float) Math.pow(1.0F - LIFT_EASE, Math.max(0L, ticks));
    }

    /**
     * 浮动相位（弧度），供没有方块实体上下文的调用方（例如 {@code Block#animateTick} 的粒子）使用。
     * <p>
     * 约定：调用方按"完全升起之后"取相位，所以这里不接受 {@code spinTime}。两个原因——
     * 一来粒子本来就只在激活时撒，那时转子早已升到位；二来相位若掺进 {@code spinTime}，
     * 方块实体没 tick 过时 {@code spinTime} 为 0，粒子环的相位就会和实际转子差一截。
     * <p>
     * 未激活时返回 0：相位冻住。
     */
    public static float bobPhaseAt(long gameTime, boolean powered) {
        if (!powered) {
            return 0.0F;
        }
        return phaseAfter(gameTime - activationTickOf(gameTime),
                FocalDecayConfig.OBSERVER_CORE_BOB_PERIOD_TICKS.get());
    }

    /** 由"进入当前 powered 状态起已过的 tick 数"与配置周期算出相位（取模，整数转 float 无精度损失）。 */
    public static float phaseAfter(long ticks, double periodTicks) {
        double period = Math.max(1.0D, periodTicks);
        return (float) ((Math.max(0L, ticks) % (long) period) / period * Mth.TWO_PI);
    }

    /** 浮动偏移：随升起淡入淡出，避免激活瞬间"啪"地跳一下。 */
    public static float bobOffsetAt(float lift, float bobPhase) {
        float amplitude = FocalDecayConfig.OBSERVER_CORE_BOB_AMPLITUDE.get().floatValue();
        return lift * amplitude * Mth.sin(bobPhase);
    }

    /** 从某一 tick 起"完全升起"的判据，见 {@link #ACTIVATION_DELAY}。 */
    public static long activationTickOf(long gameTime) {
        return gameTime - ACTIVATION_DELAY;
    }
}
