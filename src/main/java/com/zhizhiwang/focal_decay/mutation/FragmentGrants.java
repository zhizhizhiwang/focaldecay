package com.zhizhiwang.focal_decay.mutation;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 里程碑语义碎片发放（背包优先，背包满则掉落在地）。 */
public final class FragmentGrants {

    private FragmentGrants() {
    }

    public static void grant(ServerPlayer player, Item fragment) {
        ItemStack stack = new ItemStack(fragment);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(
                Component.translatable("message.focal_decay.fragment_milestone", stack.getHoverName()), true);
    }
}
