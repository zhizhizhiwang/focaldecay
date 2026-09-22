package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * 失焦遮罩（{@code observer_veil}）后处理：加载、呼吸动画、重聚焦淡出与资源重载重建。
 * <p>
 * <b>为什么单独一个类</b>：这一段是整个 mod 里<b>版本最易碎</b>的代码——它直接操作
 * Blaze3D 的后处理链（{@code PostChain} / {@code RenderTarget}），而 26.2 把渲染后端
 * 连同 {@code TextureFormat}、{@code VertexFormat}、{@code BindGroupLayout} 一起重写了，
 * OpenGL 后端还计划移除。把"什么时候该画多强"（本类）与"幽灵缓存/表面扫描"
 * （{@link ClientRenderCache}）分开之后，移植时只需要改这一个文件里的
 * {@link #ensureLoaded} / {@link #update} 两个方法，不必碰 1200 行的缓存逻辑。
 * <p>
 * 调用时机由 {@code GameRendererMixin} 决定：在 {@code renderLevel} 的 TAIL，
 * 也就是<b>第一人称手部渲染之后</b>。原版顺序是
 * {@code LevelRenderer.renderLevel → 手部 → 后处理 → bindWrite}，挂在 AFTER_LEVEL
 * 会让后面的手部拿到错误的目标/状态（表现为手不显示）。
 * <p>
 * <b>本类不做任何安全判断</b>（有没有世界、有没有别的后处理效果、开关是否打开）：
 * 那些判断留在 {@link ClientRenderCache} 里，本类只被"已经决定要画"的路径调用。
 */
@OnlyIn(Dist.CLIENT)
final class ObserverVeil {

    private static final Logger LOGGER = FocalDecay.LOGGER;

    /** 后处理链定义（{@code assets/focal_decay/shaders/post/observer_veil.json}）。 */
    private static final ResourceLocation CHAIN_ID =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "shaders/post/observer_veil.json");

    /**
     * 一次呼吸循环的时长（秒）。20 秒：足够慢，让"浓度变化"像潮汐而不是脉动。
     * <p>
     * 调参记录：实际 1 秒（单位换算错误所致，像脉动）→ 20 秒。中间试过"12 秒""15 秒"，
     * 但那时单位是错的，数值没有参考价值。
     */
    private static final float VEIL_CYCLE_SECONDS = 20.0F;
    /**
     * 呼吸幅度。0.85 ± 0.15 是"能察觉在变、但不会去数它"的档位；
     * 更大的幅度（0.75 ± 0.25）配合波纹会显得一下一下地脉动。
     */
    private static final float VEIL_BREATHE_MID = 0.85F;
    private static final float VEIL_BREATHE_AMPLITUDE = 0.15F;

    /** 单帧真实时间上限（秒）：掉帧/暂停回来时不要让相位一步跳过大半圈。 */
    private static final float MAX_FRAME_SECONDS = 2.0F / 20.0F;

    private PostChain chain;
    /** 加载时用的资源管理器：换过（F3+T 资源重载）就说明 PostChain 里的 shader 已经过期。 */
    private ResourceManager loadedFrom;
    private boolean loadFailed;
    /** 自建的连续时间（秒）。只增不回绕，见 {@link #update} 里关于内置 Time 的说明。 */
    private float elapsedSeconds;
    private int width;
    private int height;
    /**
     * 重聚焦（观测者上线）后遮罩的残留强度：1 = 全强度，0 = 已完全移除。
     * 每帧按 {@code postProcessRefocusFadeTicks} 递减，到 0 就卸载整个 PostChain，
     * 连每帧一次的全屏 pass 都不再付。世界重新回到失焦状态（或开关被打开）时复位为 1。
     */
    private float refocusFade = 1.0F;

    /** 呼吸曲线：cos 使往复两端平滑（速度为零），节拍均匀。 */
    private static float breatheFor(float phase) {
        return VEIL_BREATHE_MID + VEIL_BREATHE_AMPLITUDE * Mth.cos(phase * Mth.TWO_PI);
    }

    /**
     * 每帧推进遮罩。
     *
     * @param frameDeltaTicks 每帧真实时间增量（{@code DeltaTracker#getRealtimeDeltaTicks}），
     *                        单位是<b>刻</b>而不是秒，见下方计时说明
     * @param observerOnline  观测者核心是否已上线。为 true 且配置要求淡出时，
     *                        遮罩按 {@code post_process_refocus_fade_ticks} 淡到 0 并卸载
     */
    void update(float frameDeltaTicks, boolean observerOnline) {
        Minecraft mc = Minecraft.getInstance();

        // 每帧真实时间（秒）。动画计时与遮罩淡出都要用，所以先算出来。
        float frameSeconds = Mth.clamp(frameDeltaTicks, 0.0F, MAX_FRAME_SECONDS * 20.0F) / 20.0F;

        // ── 重聚焦之后移除遮罩 ───────────────────────────────────────────────
        // 遮罩画的就是"失焦带来的不安定"，观测者核心上线之后这份不安定已经结束了，
        // 继续留着抖动属于漏做状态处理。默认淡出（也遮住核心激活那一瞬的硬切）。
        if (!FocalDecayConfig.POST_PROCESS_AFTER_REFOCUS.get() && observerOnline) {
            int fadeTicks = Math.max(0, FocalDecayConfig.POST_PROCESS_REFOCUS_FADE_TICKS.get());
            if (refocusFade > 0.0F) {
                refocusFade = fadeTicks <= 0
                        ? 0.0F
                        : Math.max(0.0F, refocusFade - frameSeconds / (fadeTicks / 20.0F));
            }
            if (refocusFade <= 0.0F) {
                if (chain != null) {
                    close();
                    LOGGER.info("Focal Decay: observer veil removed after refocus");
                }
                return;
            }
        } else {
            // 开关打开，或世界又回到失焦状态：恢复全强度（重新加载由下面的分支负责）。
            refocusFade = 1.0F;
        }

        if (!ensureLoaded(mc)) {
            return;
        }

        // ── 动画计时 ─────────────────────────────────────────────────────────
        // 时间源与单位，两个都必须正确：
        //
        // 1) 用「每帧真实时间」而不是游戏 tick 时间。累加 DeltaTracker#getGameTimeDeltaTicks() 时，
        //    它只在发生 tick 的那一帧返回 1、其余帧返回 0，相位呈锯齿状推进。
        //
        // 2) getRealtimeDeltaTicks() 的单位是 <b>tick</b>，不是秒：源码是
        //    (time - lastUiMs) / msPerTick，而 msPerTick = 1000/20 = 50ms。
        //    60fps 下一帧 16.7ms → 0.333，即每秒累加 20。换算成秒要 / 20。
        elapsedSeconds += frameSeconds;
        float phase = (elapsedSeconds / VEIL_CYCLE_SECONDS) % 1.0F;

        float intensity = FocalDecayConfig.POST_INTENSITY.get().floatValue();
        // Fade 在 shader 里同时乘在漂移、色散、着色三项上，所以淡到 0 就是"整个效果消失"，
        // 而不是只去掉色调、留下抖动。
        chain.setUniform("Fade", Mth.clamp(intensity * breatheFor(phase) * refocusFade, 0.0F, 1.0F));

        // ⚠️ 波纹必须用自建的连续时间 uniform，<b>不能用内置的 Time</b>。
        // PostChain 每帧把 Time 归一化到 [0,1) 并在满 20 tick 时硬回绕：
        //     this.time += partialTicks;
        //     while (this.time > 20.0F) { this.time -= 20.0F; }
        //     postpass.process(this.time / 20.0F);
        // 于是 shader 里任何 `Time * f` 在回绕点的相位差都是 2π·f —— 只有 f 取整数才连续。
        // 换句话说：<b>用 Time 就永远做不出周期长于 1 秒的平滑动画</b>。
        chain.setUniform("TotalTime", elapsedSeconds);
        chain.process(frameDeltaTicks);
        // 与原版 postEffect.process 之后一致：恢复主渲染目标绑定，供后续手部/UI 使用
        mc.getMainRenderTarget().bindWrite(true);
    }

    /**
     * 懒加载 + 资源重载重建 + 窗口尺寸同步。
     *
     * @return 是否已经可以 {@code setUniform/process}
     */
    private boolean ensureLoaded(Minecraft mc) {
        ResourceManager resourceManager = mc.getResourceManager();
        if (chain != null && resourceManager != loadedFrom) {
            close(); // 资源重载（F3+T）后重建
        }
        if (chain == null) {
            try {
                chain = new PostChain(
                        mc.getTextureManager(),
                        resourceManager,
                        mc.getMainRenderTarget(),
                        CHAIN_ID);
                chain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
                loadedFrom = resourceManager;
                width = mc.getWindow().getWidth();
                height = mc.getWindow().getHeight();
                loadFailed = false;
                LOGGER.info("Focal Decay: observer veil shader loaded");
            } catch (Exception e) {
                if (!loadFailed) {
                    LOGGER.warn("Failed to load observer veil shader", e);
                    loadFailed = true;
                }
                close();
                return false;
            }
        }

        // 窗口缩放时同步后处理目标尺寸，否则输出会被错误拉伸/缩放
        int windowWidth = mc.getWindow().getWidth();
        int windowHeight = mc.getWindow().getHeight();
        if (windowWidth != width || windowHeight != height) {
            chain.resize(windowWidth, windowHeight);
            width = windowWidth;
            height = windowHeight;
        }
        return true;
    }

    /** 卸载后处理链（开关关闭、离开世界、淡出结束、资源重载、加载失败都会走到这里）。 */
    void close() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        loadedFrom = null;
    }
}
