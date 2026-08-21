package com.zhizhiwang.focal_decay.client.screen;

import com.zhizhiwang.focal_decay.menu.AnchorPrototypeMenu;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.GuidedConcept;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 原型机 GUI 屏幕（暂用原版容器贴图占位，里程碑 3 换专属贴图）。
 */
@OnlyIn(Dist.CLIENT)
public class AnchorPrototypeScreen extends AbstractContainerScreen<AnchorPrototypeMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("focal_decay", "textures/gui/anchor_prototype.png");

    public AnchorPrototypeScreen(AnchorPrototypeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ItemStack model = this.menu.getSlot(0).getItem();
        ObserverModelData data = ObserverModelItem.getData(model);
        if (data != null && ObserverModelData.TYPE_BIO.equals(data.type())) {
            graphics.drawString(this.font,
                    Component.translatable("gui.focal_decay.bio_energy",
                            this.menu.getBioEnergy(), this.menu.getBioCapacity()),
                    this.leftPos + 8, this.topPos + 58, 0x404040, false);
            if (this.menu.getBioEnergy() <= 0) {
                graphics.drawString(this.font,
                        Component.translatable("gui.focal_decay.bio_energy_empty"),
                        this.leftPos + 8, this.topPos + 68, 0xFF5555, false);
            }
        }
        if (data != null && ObserverModelData.TYPE_GUIDED.equals(data.type())) {
            if (data.concept().isEmpty()) {
                graphics.drawString(this.font,
                        Component.translatable("gui.focal_decay.guided_invalid"),
                        this.leftPos + 8, this.topPos + 58, 0xFF5555, false);
            } else {
                graphics.drawString(this.font,
                        Component.translatable("gui.focal_decay.guided_concept",
                                GuidedConcept.displayName(data.concept()),
                                Math.round(data.stabilityStrength() * 100)),
                        this.leftPos + 8, this.topPos + 58, 0x404040, false);
            }
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
