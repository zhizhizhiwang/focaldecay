package com.zhizhiwang.focal_decay.client;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.screen.AnchorPrototypeScreen;
import com.zhizhiwang.focal_decay.client.screen.TrainingTerminalScreen;
import com.zhizhiwang.focal_decay.menu.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** 客户端 Mod 总线事件（屏幕注册等）。 */
@EventBusSubscriber(modid = FocalDecay.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ANCHOR_PROTOTYPE.get(), AnchorPrototypeScreen::new);
        event.register(ModMenus.TRAINING_TERMINAL.get(), TrainingTerminalScreen::new);
    }
}
