package com.zhizhiwang.focal_decay.item;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FocalDecay.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FOCAL_DECAY_TAB =
            CREATIVE_MODE_TABS.register("focal_decay_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.focal_decay"))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(() -> ModItems.STABLE_ANCHOR.toStack())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.STABLE_ANCHOR.get());
                        output.accept(ModItems.MUTATION_CONTROLLER.get());
                        output.accept(ModItems.OBSERVER_CORE.get());
                        output.accept(ModItems.FRAGMENT_ROSE.get());
                        output.accept(ModItems.FRAGMENT_THRONE.get());
                        output.accept(ModItems.FRAGMENT_SEMANTIC.get());
                        output.accept(ModItems.FRAGMENT_42MS.get());
                        output.accept(ModItems.FRAGMENT_CRYSTAL.get());
                        output.accept(ModItems.FRAGMENT_AARON.get());
                        output.accept(ModItems.FRAGMENT_CHENG.get());
                        output.accept(ModItems.REBUILT_OBSERVER_PROTOCOL.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
