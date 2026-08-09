package com.zhizhiwang.focal_decay.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 语义碎片物品。每个碎片带有一条 Lore，通过 lang key 提供（支持中英双语）。
 */
public class SemanticFragmentItem extends Item {
    private final String loreKey;

    public SemanticFragmentItem(Properties properties, String loreKey) {
        super(properties);
        this.loreKey = loreKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable(loreKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
