package com.zhizhiwang.focal_decay.client.facade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 屏幕与界面绘制的出口：那些"签名在版本之间变过、但语义只有一种"的调用。
 * <p>
 * 放进来的判据是<b>"改版时签名变了、调用点却只是一行"</b>：
 * <ul>
 *   <li>{@link #sendButtonClick} —— 界面按钮的回传包。26.2 把 GUI 相关方法搬去了
 *       {@code Gui}/{@code Hud}，按钮 → 包的这条链路是每次 GUI 重组都会碰到的地方；
 *       两个 Screen 原本各写了一份一模一样的实现。</li>
 *   <li>{@link #drawPseudoSlot} —— JEI 类别里"画一个槽位底"的纯像素操作。
 *       两个 JEI 类别各复制了一份；这里收成一处，顺便把它与真正的槽位实现解耦
 *       （我们画的是装饰，不参与 JEI 的槽位布局）。</li>
 * </ul>
 * 刻意<b>不</b>把 {@code blit} / {@code fill} 这类基础绘制也包一层：它们的调用点大多是
 * "一行一图"，包起来只是增加一层跳转，并不减少移植工作量。
 */
@OnlyIn(Dist.CLIENT)
public final class VanillaGui {

    private VanillaGui() {
    }

    /**
     * 当前屏幕 → 服务端的按钮点击包（容器界面的自定义按钮都走这条）。
     * 没有玩家连接时静默忽略：界面可能在断线帧里还被画到。
     */
    public static void sendButtonClick(int containerId, int buttonId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.send(new ServerboundContainerButtonClickPacket(containerId, buttonId));
        }
    }

    /**
     * 画一个 18×18 的"槽位底"（外框 + 内底），以 ({@code centerX}, {@code centerY}) 为中心。
     * 只用于自绘背景的类别；真正的物品槽位由 JEI 的 {@code addSlot} 负责。
     */
    public static void drawPseudoSlot(GuiGraphics graphics, int centerX, int centerY,
                                      int frameColor, int innerColor) {
        int left = centerX - 1;
        int top = centerY - 1;
        graphics.fill(left, top, left + 18, top + 18, frameColor);
        graphics.fill(left + 1, top + 1, left + 17, top + 17, innerColor);
    }
}
