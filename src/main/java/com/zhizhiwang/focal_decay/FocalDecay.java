package com.zhizhiwang.focal_decay;

import com.mojang.logging.LogUtils;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.block.entity.ModBlockEntities;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ModDataGenerator;
import com.zhizhiwang.focal_decay.data.ModDataComponents;
import com.zhizhiwang.focal_decay.data.recipe.ModRecipeSerializers;
import com.zhizhiwang.focal_decay.item.ModCreativeTabs;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.menu.ModMenus;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import com.zhizhiwang.focal_decay.structure.ModStructures;
import com.zhizhiwang.focal_decay.attachment.ModAttachments;
import com.zhizhiwang.focal_decay.command.ModCommands;
import com.zhizhiwang.focal_decay.mutation.InteractionHandler;
import com.zhizhiwang.focal_decay.mutation.ModelTrainingHandler;
import com.zhizhiwang.focal_decay.mutation.BioStabilizerHandler;
import com.zhizhiwang.focal_decay.mutation.ThroneRitualHandler;
import com.zhizhiwang.focal_decay.mutation.DragonDropHandler;
import com.zhizhiwang.focal_decay.mutation.TotalStabilityFieldHandler;
import com.zhizhiwang.focal_decay.mutation.DoomsdayHandler;
import com.zhizhiwang.focal_decay.mutation.MutationEventHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(FocalDecay.MODID)
public final class FocalDecay {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "focal_decay";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public FocalDecay(IEventBus modEventBus, ModContainer modContainer) {
        // Register all Deferred Registers to the mod event bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModRecipeSerializers.RECIPE_SERIALIZERS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModDataComponents.DATA_COMPONENT_TYPES.register(modEventBus);
        ModStructures.STRUCTURE_TYPES.register(modEventBus);
        ModStructures.PIECE_TYPES.register(modEventBus);
        ModStructures.PLACEMENT_TYPES.register(modEventBus);

        // Register data generators (runData)
        ModDataGenerator.register(modEventBus);

        // Register network payloads (NeoForge 21.1 Payload API)
        modEventBus.addListener(ModNetwork::registerPayloads);

        // Register game event handlers
        NeoForge.EVENT_BUS.register(MutationEventHandler.class);
        NeoForge.EVENT_BUS.register(InteractionHandler.class);
        NeoForge.EVENT_BUS.register(DoomsdayHandler.class);
        NeoForge.EVENT_BUS.register(ModCommands.class);
        NeoForge.EVENT_BUS.register(ModelTrainingHandler.class);
        NeoForge.EVENT_BUS.register(BioStabilizerHandler.class);
        NeoForge.EVENT_BUS.register(ThroneRitualHandler.class);
        NeoForge.EVENT_BUS.register(DragonDropHandler.class);
        NeoForge.EVENT_BUS.register(TotalStabilityFieldHandler.class);

        // Register config specs
        modContainer.registerConfig(ModConfig.Type.SERVER, FocalDecayConfig.SERVER_SPEC);
        modContainer.registerConfig(ModConfig.Type.COMMON, FocalDecayConfig.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, FocalDecayConfig.CLIENT_SPEC);
    }
}
