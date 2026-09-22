package com.zhizhiwang.focal_decay.client.screen;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.facade.VanillaGui;
import com.zhizhiwang.focal_decay.client.facade.VanillaText;
import com.zhizhiwang.focal_decay.menu.TrainingTerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 训练终端 GUI 屏幕（暂用原版容器贴图占位）。
 */
@OnlyIn(Dist.CLIENT)
public class TrainingTerminalScreen extends AbstractContainerScreen<TrainingTerminalMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "textures/gui/training_terminal.png");

    public TrainingTerminalScreen(TrainingTerminalMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.focal_decay.training_semantic"),
                        b -> VanillaGui.sendButtonClick(this.menu.containerId, 0))
                .bounds(this.leftPos + 8, this.topPos + 20, 78, 20)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.focal_decay.training_guided"),
                        b -> VanillaGui.sendButtonClick(this.menu.containerId, 1))
                .bounds(this.leftPos + 90, this.topPos + 20, 78, 20)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.focal_decay.training_finish"),
                        b -> VanillaGui.sendButtonClick(this.menu.containerId, 2))
                .bounds(this.leftPos + 8, this.topPos + 44, 160, 20)
                .build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        VanillaText.drawShadowed(graphics,
                Component.translatable("gui.focal_decay.energy", this.menu.getEnergy()),
                this.leftPos + 100, this.topPos + 70, 0xFFFFFF);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
