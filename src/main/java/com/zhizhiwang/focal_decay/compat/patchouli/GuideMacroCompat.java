package com.zhizhiwang.focal_decay.compat.patchouli;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import net.minecraft.network.chat.Component;
import vazkii.patchouli.api.PatchouliAPI;

/**
 * Patchouli 手册宏注册（可选依赖）。
 * <p>
 * <b>为什么必须是独立的一个类：</b>lambda 会被 javac 编译成一个合成方法，其签名里带着
 * Patchouli 的类型（{@code IStyleStack}）。若这个 lambda 写在 {@code ClientSetup} 里——
 * 而 {@code ClientSetup} 挂着 {@code @EventBusSubscriber}、NeoForge 会<b>无条件加载</b>它——
 * 那么 JVM 在校验该类时就要解析 {@code IStyleStack}，Patchouli 缺席时直接
 * {@code NoClassDefFoundError}，连 mod 构造都会失败（实测：整包启动崩在
 * "Failed to register automatic subscribers"）。
 * <p>
 * 关键点：<b>失败发生在类加载/校验阶段，早于任何运行时代码</b>，
 * 所以 {@code ModList.isLoaded(...)} 判断和 {@code try/catch} 都拦不住——
 * 必须把这类引用隔离到一个"只在确认装了 Patchouli 之后才会被加载"的类里。
 * 调用方见 {@code ClientSetup#registerGuideMacros}。
 */
public final class GuideMacroCompat {

    /** 手册正文里写 {@code $(focal_decay:schedule)}。 */
    private static final String SCHEDULE_MACRO = FocalDecay.MODID + ":schedule";

    private GuideMacroCompat() {
    }

    /** 注册"阶段日程"宏。只在 Patchouli 存在时被调用。 */
    public static void registerScheduleMacro() {
        PatchouliAPI.get().registerCommand(SCHEDULE_MACRO,
                style -> Component.translatable("book.focal_decay.schedule",
                        FocalDecayConfig.STAGE2_DAY.get(),
                        FocalDecayConfig.STAGE3_DAY.get()).getString());
        FocalDecay.LOGGER.info("Registered Patchouli macro {}", SCHEDULE_MACRO);
    }
}
