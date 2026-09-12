package com.zhizhiwang.focal_decay.compat.jei;

import com.zhizhiwang.focal_decay.FocalDecay;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 特殊配方的 JEI 类别（复制模型 / 碎片喂食）。
 * <p>
 * 这两条配方用的是自定义 {@code CustomRecipe} 序列化器，JEI <b>不会</b>自动展示，
 * 必须像这样显式登记，否则玩家在 JEI 里完全看不到"训练模型可以复制"和"碎片可以喂给候选体"。
 * <p>
 * 布局：两个输入槽 → 输出槽，下方一行说明。输入槽支持放多个候选物品
 * （例如碎片喂食要列出全部七枚碎片，用 {@code addItemStacks} 放在同一个槽里轮播）。
 */
public final class SpecialRecipeCategory implements IRecipeCategory<SpecialRecipeCategory.Entry> {

    public static final RecipeType<Entry> COPY_MODEL =
            RecipeType.create(FocalDecay.MODID, "copy_model", Entry.class);
    public static final RecipeType<Entry> FEED_FRAGMENT =
            RecipeType.create(FocalDecay.MODID, "feed_fragment", Entry.class);

    private static final int WIDTH = 168;
    private static final int HEIGHT = 96;

    private static final int FIRST_X = 30;
    private static final int SECOND_X = 62;
    private static final int OUTPUT_X = 110;
    private static final int SLOT_Y = 26;

    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 54;
    private static final int TEXT_WIDTH = 152;

    private static final int COLOR_PANEL = 0xFFE6E4DE;
    private static final int COLOR_PANEL_EDGE = 0xFF8B8B8B;
    private static final int COLOR_TEXT_AREA = 0xFFEDEBE5;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_SLOT_INNER = 0xFF373737;
    private static final int COLOR_TEXT = 0x404040;

    /**
     * 一条特殊配方记录。
     *
     * @param inputs  输入物品（每个元素对应一个槽位；同一槽的多个候选物品写进同一个 List）
     * @param output  产出
     * @param noteKey 说明文案的 lang key
     */
    public record Entry(List<List<ItemStack>> inputs, ItemStack output, String noteKey) {
    }

    private final RecipeType<Entry> type;
    private final Component title;
    private final IDrawable icon;

    public SpecialRecipeCategory(IGuiHelper guiHelper, RecipeType<Entry> type,
                                 String titleKey, ItemStack iconStack) {
        this.type = type;
        this.title = Component.translatable(titleKey);
        this.icon = guiHelper.createDrawableItemStack(iconStack);
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    /** 自绘背景，关闭 JEI 那圈会向内占边距的边框。 */
    @Override
    public boolean needsRecipeBorder() {
        return false;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Entry recipe, IFocusGroup focuses) {
        List<List<ItemStack>> inputs = recipe.inputs();
        if (!inputs.isEmpty()) {
            // 第一个槽放在左边，第二个槽放中间（两个槽都向右偏一格，留出与输出槽的间距）
            builder.addInputSlot(FIRST_X, SLOT_Y).addItemStacks(inputs.get(0));
        }
        if (inputs.size() > 1) {
            builder.addInputSlot(SECOND_X, SLOT_Y).addItemStacks(inputs.get(1));
        }
        builder.addOutputSlot(OUTPUT_X, SLOT_Y).addItemStack(recipe.output());
    }

    @Override
    public void draw(Entry recipe, IRecipeSlotsView slots, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        guiGraphics.fill(0, 0, WIDTH, HEIGHT, COLOR_PANEL_EDGE);
        guiGraphics.fill(1, 1, WIDTH - 1, HEIGHT - 1, COLOR_PANEL);

        drawSlot(guiGraphics, FIRST_X, SLOT_Y);
        if (recipe.inputs().size() > 1) {
            drawSlot(guiGraphics, SECOND_X, SLOT_Y);
        }
        drawSlot(guiGraphics, OUTPUT_X, SLOT_Y);

        // 输入 → 输出 的箭头
        Font font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, "→", OUTPUT_X - 16, SLOT_Y - 4, COLOR_TEXT, false);

        guiGraphics.fill(TEXT_X, TEXT_Y - 6, TEXT_X + TEXT_WIDTH, HEIGHT - 6, COLOR_TEXT_AREA);
        int y = TEXT_Y;
        for (var visual : font.split(Component.translatable(recipe.noteKey()), TEXT_WIDTH)) {
            guiGraphics.drawString(font, visual, TEXT_X, y, COLOR_TEXT, false);
            y += font.lineHeight + 2;
        }
    }

    private static void drawSlot(GuiGraphics guiGraphics, int centerX, int centerY) {
        int left = centerX - 1;
        int top = centerY - 1;
        guiGraphics.fill(left, top, left + 18, top + 18, COLOR_SLOT);
        guiGraphics.fill(left + 1, top + 1, left + 17, top + 17, COLOR_SLOT_INNER);
    }
}
