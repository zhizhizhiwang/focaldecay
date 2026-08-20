package com.zhizhiwang.focal_decay.data;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENT_TYPES =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FocalDecay.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ObserverModelData>> OBSERVER_MODEL_DATA =
            DATA_COMPONENT_TYPES.register("observer_model_data", () ->
                    DataComponentType.<ObserverModelData>builder()
                            .persistent(ObserverModelData.CODEC)
                            .networkSynchronized(ObserverModelData.STREAM_CODEC)
                            .build());

    private ModDataComponents() {
    }
}
