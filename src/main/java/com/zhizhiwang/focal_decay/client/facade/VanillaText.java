package com.zhizhiwang.focal_decay.client.facade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 文本绘制的唯一出口。
 * <p>
 * <b>为什么值得单独一层</b>：{@code Font} 的绘制方法正是 26.2 里被<b>整体删除</b>的那一批
 * （迁移 primer：{@code Font} 不再直接画字，改成 {@code prepareText} 产出 {@code PreparedText}，
 * 再由 {@code GlyphVisitor} 逐个 glyph 提交到渲染缓冲）。也就是说，从 1.21.1 往上走，
 * 每一处 {@code graphics.drawString(font, ...)} 都要重写；而"折行 + 逐行绘制"这种循环
 * 会让重写量按调用点翻倍。
 * <p>
 * 把调用收进这里之后，移植时只需要改本文件的 4 个方法：
 * <ul>
 *   <li>{@link #draw} / {@link #drawShadowed} —— 单行；</li>
 *   <li>{@link #drawWrapped} —— 折行成块，内部完成 split + 逐行 + 行距；</li>
 *   <li>{@link #split} / {@link #lineHeight} —— 需要自己控制坐标的调用方（例如 JEI 箭头标签）。</li>
 * </ul>
 * 返回类型保留 {@link FormattedCharSequence}：本层不负责"文本长什么样"，
 * 只负责"怎么把它画到屏幕上"，所以不引入自定义的文本中间类型。
 * <p>
 * 仅客户端：本类引用 {@code GuiGraphics}。
 */
@OnlyIn(Dist.CLIENT)
public final class VanillaText {

    /** 折行块的行距（在字号高度之外额外留的像素）。 */
    public static final int LINE_GAP = 2;

    private VanillaText() {
    }

    /** 当前窗口的字体（行高、折行宽度都要用它）。空世界（主菜单/加载中）时为 null。 */
    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** 单行绘制，带阴影。用于需要从背景里"浮起来"的标题类文本。 */
    public static void drawShadowed(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(font(), text, x, y, color, true);
    }

    /** 单行绘制，不带阴影。深色面板上的正文用它。 */
    public static void draw(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(font(), text, x, y, color, false);
    }

    /** 单行绘制已排版好的片段（{@link #split} 的产物）。 */
    public static void draw(GuiGraphics graphics, FormattedCharSequence text, int x, int y, int color) {
        graphics.drawString(font(), text, x, y, color, false);
    }

    /**
     * 按宽度折行后在 ({@code x}, {@code y}) 起向下逐行绘制，返回下一行的 y（调用方可以接着往下排）。
     * <p>
     * 不要用 {@code graphics.drawWordWrap}：那条路径只能画一个颜色、也拿不到行数，
     * 面板上要"折行 + 继续排版"的地方会立刻卡住。这里统一走
     * {@link Font#split} → 逐行 → {@link Font#lineHeight} + {@link #LINE_GAP}。
     */
    public static int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        Font font = font();
        int lineY = y;
        for (FormattedCharSequence line : font.split(text, width)) {
            graphics.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight + LINE_GAP;
        }
        return lineY;
    }

    /** 折行但不绘制：给需要自己控制每一行坐标的调用方（JEI 类别自绘）。 */
    public static List<FormattedCharSequence> split(Component text, int width) {
        return font().split(text, width);
    }

    /** 字号高度（不含 {@link #LINE_GAP}）。 */
    public static int lineHeight() {
        return font().lineHeight;
    }

    /** 折行块占用的总高度：行数 × (行高 + 行距)。用于给面板预留空间。 */
    public static int wrappedHeight(Component text, int width) {
        Font font = font();
        return font.split(text, width).size() * (font.lineHeight + LINE_GAP);
    }
}
