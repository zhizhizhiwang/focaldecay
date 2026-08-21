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

/** 观测者核心 GUI 屏幕：显示"离线/在线"状态与激活按钮。 */
@OnlyIn(Dist.CLIENT)
public class ObserverCoreScreen extends AbstractContainerScreen<ObserverCoreMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("focal_decay", "textures/gui/anchor_prototype.png");

    public ObserverCoreScreen(ObserverCoreMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.focal_decay.core_install"),
                        b -> sendButton(0))
                .bounds(this.leftPos + 8, this.topPos + 40, 160, 20)
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        boolean online = this.menu.isPowered();
        graphics.drawString(this.font,
                Component.translatable(online ? "gui.focal_decay.core_online" : "gui.focal_decay.core_offline"),
                this.leftPos + 8, this.topPos + 22, online ? 0x55FF55 : 0xFF5555, false);
        if (!online) {
            graphics.drawString(this.font,
                    Component.translatable("gui.focal_decay.core_hint"),
                    this.leftPos + 8, this.topPos + 68, 0x404040, false);
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
