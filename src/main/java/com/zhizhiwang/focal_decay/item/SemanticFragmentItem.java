package com.zhizhiwang.focal_decay.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 语义碎片物品。每个碎片带有一条 Lore 与一条来源说明，均通过 lang key 提供（支持中英双语）。
 * <p>
 * 来源说明是必要的：七枚碎片全部来自里程碑触发而非配方，玩家拿到手时无从得知出处
 * （JEI 的「语义碎片来源」类别提供同样的信息，这里保证不装 JEI 也能看到）。
 */
public class SemanticFragmentItem extends Item {
    private final String loreKey;
    private final String sourceKey;

    public SemanticFragmentItem(Properties properties, String loreKey, String sourceKey) {
        super(properties);
        this.loreKey = loreKey;
        this.sourceKey = sourceKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable(loreKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltipComponents.add(Component.translatable("tooltip.focal_decay.fragment_source",
                Component.translatable(sourceKey)).withStyle(ChatFormatting.DARK_GRAY));
    }
}
