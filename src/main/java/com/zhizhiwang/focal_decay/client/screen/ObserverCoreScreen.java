package com.zhizhiwang.focal_decay.client.screen;

import com.zhizhiwang.focal_decay.menu.ObserverCoreMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 观测者核心 GUI 屏幕：显示"离线/在线"状态与激活按钮。
 *
 * <p>这是一个**无槽位**界面（{@link ObserverCoreMenu} 一个槽都没加），所以三件事和默认行为不一样：
 * <ul>
 *   <li>不画原版的 {@code playerInventoryTitle}（"物品栏"）——它由
 *       {@link AbstractContainerScreen#renderLabels} 默认绘制，但本界面根本没有物品栏槽位，
 *       而且它的 y（{@code imageHeight - 94} = 72）正好压在提示文字上，会糊成一团。</li>
 *   <li>提示文字必须折行：那句中文有 24 个字、约 216px，比 176px 宽的面板还长，直接画会捅到界面外。</li>
 *   <li>贴图是深色面板（rgb 35,39,44），文字得用亮色；原版标签色 {@code 0x404040} 在这个底色上看不见。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class ObserverCoreScreen extends AbstractContainerScreen<ObserverCoreMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("focal_decay", "textures/gui/observer_core.png");

    /** 贴图是 256x256 画布，实际面板占左上角 176x166（{@code blit} 的 7 参数重载按 256x256 取 UV）。 */
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 166;

    /** 左右各留 8px 边距后的正文宽度。 */
    private static final int TEXT_WIDTH = PANEL_WIDTH - 16;

    // 深色底上的文字颜色。原版的 0x404040 在这个面板上几乎不可见。
    private static final int COLOR_TITLE = 0xE8E8E8;
    private static final int COLOR_HINT = 0x9AA0A6;
    private static final int COLOR_ONLINE = 0x55FF55;
    private static final int COLOR_OFFLINE = 0xFF5555;

    private static final int STATUS_Y = 30;
    private static final int BUTTON_Y = 56;
    private static final int HINT_Y = 92;

    public ObserverCoreScreen(ObserverCoreMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // 和贴图里的面板尺寸对齐（默认值恰好也是 176x166，写出来是为了把这层耦合摆明）
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.focal_decay.core_install"),
                        b -> sendButton(0))
                .bounds(this.leftPos + 8, this.topPos + BUTTON_Y, TEXT_WIDTH, 20)
                .build());
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.connection.send(new ServerboundContainerButtonClickPacket(this.menu.containerId, id));
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
    }

    /**
     * 只画标题，刻意不调 {@code super}：原版实现会额外画 {@code playerInventoryTitle}（"物品栏"），
     * 那是给带槽位的容器用的，本界面没有槽位，画出来只会浮在空处并压住提示文字。
     *
     * <p>坐标系：vanilla 在调用本方法前已经把 pose 平移到 {@code (leftPos, topPos)}
     * （{@code AbstractContainerScreen#render} 里 translate 在前、renderLabels 在后），
     * 所以这里的坐标是**相对面板左上角**的，不要再加 {@code leftPos}/{@code topPos}。
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, COLOR_TITLE, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        boolean online = this.menu.isPowered();
        graphics.drawString(this.font,
                Component.translatable(online ? "gui.focal_decay.core_online" : "gui.focal_decay.core_offline"),
                this.leftPos + 8, this.topPos + STATUS_Y, online ? COLOR_ONLINE : COLOR_OFFLINE, false);
        if (!online) {
            // 折行而不是硬画一行：这句中英文都比面板宽。
            graphics.drawWordWrap(this.font,
                    Component.translatable("gui.focal_decay.core_hint"),
                    this.leftPos + 8, this.topPos + HINT_Y, TEXT_WIDTH, COLOR_HINT);
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
