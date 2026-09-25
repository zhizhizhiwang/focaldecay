package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.entity.ModBlockEntities;
import com.zhizhiwang.focal_decay.client.particle.ObserverSparkParticle;
import com.zhizhiwang.focal_decay.particle.ModParticles;
import com.zhizhiwang.focal_decay.client.screen.AnchorPrototypeScreen;
import com.zhizhiwang.focal_decay.client.screen.ObserverCoreScreen;
import com.zhizhiwang.focal_decay.client.screen.TrainingTerminalScreen;
import com.zhizhiwang.focal_decay.menu.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * 客户端 Mod 总线事件（屏幕注册、可选依赖的客户端钩子等）。
 * <p>
 * 这里<b>不写 {@code bus = Bus.MOD}</b>：NeoForge 21.1 起该属性已废弃并标记待删除，
 * 会自动按事件类型判定总线（本类全是 {@code IModBusEvent}，自动落到 mod 总线）。
 */
@EventBusSubscriber(modid = FocalDecay.MODID, value = Dist.CLIENT)
public final class ClientSetup {

    private static boolean guideMacrosRegistered;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.OBSERVER_CORE.get(), ObserverCoreRenderer::new);
    }

    /**
     * 转子模型不挂在 blockstate 上（底座才挂），必须显式注册成"附加模型"，
     * BER 才能从模型管理器里取到它。
     */
    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ObserverCoreRenderer.ROTOR_IDLE_MODEL);
        event.register(ObserverCoreRenderer.ROTOR_ACTIVE_MODEL);
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.OBSERVER_SPARK.get(), ObserverSparkParticle.Provider::new);
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ANCHOR_PROTOTYPE.get(), AnchorPrototypeScreen::new);
        event.register(ModMenus.TRAINING_TERMINAL.get(), TrainingTerminalScreen::new);
        event.register(ModMenus.OBSERVER_CORE.get(), ObserverCoreScreen::new);
    }

    /**
     * 客户端诊断命令（{@code /focaldecay clientstats}）。
     * <p>
     * <b>注意总线</b>：{@link RegisterClientCommandsEvent} 是 NeoForge 总线的普通事件、
     * 不实现 {@code IModBusEvent}，所以它会自动落到 NeoForge 总线；与上面几个 mod 总线事件
     * 共存靠的是 NeoForge 21.1 按事件类型自动判定（本类不写 {@code bus = ...}）。
     * <p>
     * 真正实现在 {@link ClientStatsCommand}（客户端专属类，读 {@code ClientRenderCache}）。
     * 本类本身也只在客户端加载，所以这个转发不会把客户端类型带到专用服务器上。
     */
    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        ClientStatsCommand.register(event);
    }

    /**
     * 冒烟钩子：在标题界面出现时把 {@code clientstats} 那条代码路径真的跑一遍并写日志。
     * <p>
     * <b>为什么需要它</b>：{@code /focaldecay clientstats} 只能由真人执行，
     * 而"这条命令有没有注册成功、格式化代码会不会抛异常"是可以在无人值守时验掉的。
     * 无头客户端只到主菜单，不会说话，所以唯一能自动触发的时机就是标题界面初始化。
     * 它走的是 {@link ClientStatsCommand#smokeReport()}——与命令正文<b>同一段</b>格式化代码，
     * 所以它通过就说明命令的正文不会炸（注册本身由 Brigadier 保证：注册失败会在启动日志里报错）。
     * <p>
     * 默认关闭（{@code -Dfocaldecay.clientStatsSmoke=true}），而且 Gradle 的 JavaExec 不转发
     * 命令行 {@code -D}，要跑得先在 build.gradle 的 run 配置里转发——见 {@code AGENTS.md} §4。
     * 没有世界时读数是 0，这是<b>预期</b>：这一趟要验的是"代码路径通"而不是"数字好看"。
     */
    @SubscribeEvent
    public static void smokeClientStats(ScreenEvent.Init.Post event) {
        if (!Boolean.getBoolean("focaldecay.clientStatsSmoke")) {
            return;
        }
        if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen)) {
            return;
        }
        ClientStatsCommand.smokeReport();
    }

    /**
     * 给手册注册"阶段日程"宏（把"还能撑多久"接到 {@code stage2_day / stage3_day} 配置上）。
     * <p>
     * 之前正文硬编码"你有大约七天"，改配置就会与事实不符。宏在书内容构建时展开（每次重载都取配置当前值），
     * 所以调完 config 重启即生效，不必动文档。
     * <p>
     * 挂在 {@link RegisterMenuScreensEvent} 上是有意的：它在客户端资源重载之前触发，
     * 早于 Patchouli 构建书内容；同时已经在配置文件加载之后，能安全读 STAGE2_DAY / STAGE3_DAY。
     * <p>
     * ⚠️ <b>这里只能"转发"，不能直接写引用 Patchouli 类的 lambda。</b>
     * 本类挂着 {@code @EventBusSubscriber}，NeoForge 会无条件加载它；若 lambda 的签名里出现
     * {@code IStyleStack}，JVM 校验本类时就会去解析它，Patchouli 缺席时直接
     * {@code NoClassDefFoundError} 并拖垮整个 mod 构造 —— 那是类加载阶段的失败，
     * 这个方法体内的 {@code isLoaded} 检查与 try/catch 都还没机会执行。
     * 真正的实现放在 {@link GuideMacroCompat}，只有确认装了 Patchouli 才会被加载。
     */
    @SubscribeEvent
    public static void registerGuideMacros(RegisterMenuScreensEvent event) {
        if (guideMacrosRegistered || !ModList.get().isLoaded("patchouli")) {
            return;
        }
        try {
            com.zhizhiwang.focal_decay.compat.patchouli.GuideMacroCompat.registerScheduleMacro();
            guideMacrosRegistered = true;
        } catch (Throwable t) {
            // 配置尚未加载等异常：宏缺失只会让正文出现原样文本，不该影响启动
            FocalDecay.LOGGER.warn("Failed to register guide macro: {}", t.toString());
        }
    }
}
