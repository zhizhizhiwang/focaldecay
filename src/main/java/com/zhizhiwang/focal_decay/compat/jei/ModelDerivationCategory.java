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

/**
 * JEI 类别：观测模型派生。
 * <p>
 * 展示"OBSR 原型 → 各型号"的派生关系：原型是唯一的起点，五种型号由它经不同途径得到
 * （两种训练方向、生物稳定配方、仪式升级、候选体自主积累）。其中只有生物稳定有真实配方，
 * 其余是训练/仪式结果——JEI 无法从配方表推导，所以在这里显式列出。
 * <p>
 * 结果模型刻意放在<b>输出槽</b>：这样在 JEI 里对某个型号按 R（配方）就能查到它的来路，
 * 正是"来源"语义。若放成输入槽，JEI 会把它当成"用途"，方向就反了。
 */
public final class ModelDerivationCategory implements IRecipeCategory<ModelDerivationCategory.Entry> {

    public static final RecipeType<Entry> TYPE = RecipeType.create(
            FocalDecay.MODID, "model_derivation", Entry.class);

    private static final int WIDTH = 168;
    private static final int HEIGHT = 100;

    private static final int INPUT_X = 24;
    private static final int OUTPUT_X = 88;
    private static final int SLOT_Y = 34;
    private static final int WORKBENCH_X = 146;

    private static final int TEXT_X = 8;
    private static final int TEXT_Y = 58;
    private static final int TEXT_WIDTH = 152;

    private static final int COLOR_PANEL = 0xFFE6E4DE;
    private static final int COLOR_PANEL_EDGE = 0xFF8B8B8B;
    private static final int COLOR_TEXT_AREA = 0xFFEDEBE5;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_SLOT_INNER = 0xFF373737;
    private static final int COLOR_TEXT = 0x404040;

    private final IDrawable icon;

    /**
     * 一条派生记录。
     *
     * @param input     起点物品（原型，或未激活的 OBSR-EX）
     * @param result    派生出的物品（输出槽，因此对它按 R 可查到这条来路）
     * @param workbench 该派生所用的装置/条件（第三个槽）
     * @param noteKey   途径说明的 lang key
     */
    public record Entry(ItemStack input, ItemStack result, ItemStack workbench, String noteKey) {
    }

    public ModelDerivationCategory(IGuiHelper guiHelper, ItemStack iconStack) {
        this.icon = guiHelper.createDrawableItemStack(iconStack);
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.focal_decay.jei.model_derivation");
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
        // 输入槽用条目自带的起点物品——不能写死成原型：
        // OBSR-EX 的派生起点是"未激活的 OBSR-EX"，OBSR-3 的起点是已激活的 EX。
        builder.addInputSlot(INPUT_X, SLOT_Y).addItemStack(recipe.input());
        builder.addOutputSlot(OUTPUT_X, SLOT_Y).addItemStack(recipe.result());
        if (!recipe.workbench().isEmpty()) {
            builder.addInputSlot(WORKBENCH_X, SLOT_Y).addItemStack(recipe.workbench());
        }
    }

    @Override
    public void draw(Entry recipe, IRecipeSlotsView slots, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        guiGraphics.fill(0, 0, WIDTH, HEIGHT, COLOR_PANEL_EDGE);
        guiGraphics.fill(1, 1, WIDTH - 1, HEIGHT - 1, COLOR_PANEL);

        // 两个输入槽 + 一个输出槽的槽位底
        drawSlot(guiGraphics, INPUT_X, SLOT_Y);
        drawSlot(guiGraphics, OUTPUT_X, SLOT_Y);
        if (!recipe.workbench().isEmpty()) {
            drawSlot(guiGraphics, WORKBENCH_X, SLOT_Y);
        }
        // 结果区底色略提亮，暗示"这是产物"
        guiGraphics.fill(TEXT_X, TEXT_Y - 6, TEXT_X + TEXT_WIDTH, HEIGHT - 6, COLOR_TEXT_AREA);

        Font font = Minecraft.getInstance().font;
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
