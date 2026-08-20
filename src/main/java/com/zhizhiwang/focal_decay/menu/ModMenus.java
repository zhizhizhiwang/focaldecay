package com.zhizhiwang.focal_decay.menu;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, FocalDecay.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<AnchorPrototypeMenu>> ANCHOR_PROTOTYPE =
            MENUS.register("anchor_prototype", () -> new MenuType<>(
                    (id, inv) -> new AnchorPrototypeMenu(id, inv), FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<TrainingTerminalMenu>> TRAINING_TERMINAL =
            MENUS.register("training_terminal", () -> new MenuType<>(
                    (id, inv) -> new TrainingTerminalMenu(id, inv), FeatureFlags.VANILLA_SET));

    private ModMenus() {
    }
}
