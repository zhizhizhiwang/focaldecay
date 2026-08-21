package com.zhizhiwang.focal_decay.menu;

import com.zhizhiwang.focal_decay.mutation.ObserverCoreHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 观测者核心 GUI（设计大纲 §3.4）：无槽位，仅显示"离线/在线"状态 + 激活按钮。
 * 按钮 0 = 激活（服务端校验携带重建的观测协议）。
 */
public class ObserverCoreMenu extends AbstractContainerMenu {
    private final ContainerData coreData;
    /** 服务端核心位置（客户端占位构造为 null）。 */
    private final BlockPos pos;

    /** 客户端侧占位构造。 */
    public ObserverCoreMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, false, null);
    }

    /** 服务端构造。 */
    public ObserverCoreMenu(int containerId, Inventory playerInventory, boolean powered, BlockPos pos) {
        super(ModMenus.OBSERVER_CORE.get(), containerId);
        this.coreData = new SimpleContainerData(1);
        this.coreData.set(0, powered ? 1 : 0);
        this.pos = pos;
        this.addDataSlots(coreData);
    }

    public boolean isPowered() {
        return coreData.get(0) == 1;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0 && player instanceof ServerPlayer serverPlayer
                && player.level() instanceof ServerLevel serverLevel && pos != null) {
            return ObserverCoreHandler.tryActivate(serverLevel, pos, serverPlayer);
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
