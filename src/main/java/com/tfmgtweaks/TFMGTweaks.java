package com.tfmgtweaks;

import com.drmangotea.tfmg.base.TFMGCreativeTabs;
import com.mojang.logging.LogUtils;
import com.tfmgtweaks.advancement.TFMGTweaksTriggers;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import com.tfmgtweaks.registry.TFMGTweaksBlockEntities;
import com.tfmgtweaks.registry.TFMGTweaksBlocks;
import com.tfmgtweaks.registry.TFMGTweaksFluids;
import com.tfmgtweaks.registry.TFMGTweaksItems;
import com.tfmgtweaks.worldgen.TFMGTweaksFeatures;
import com.tfmgtweaks.worldgen.TFMGTweaksPlacementModifiers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;

@Mod(TFMGTweaks.MOD_ID)
public class TFMGTweaks {

    public static final String MOD_ID = "tfmgtweaks";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TFMGTweaks(IEventBus modEventBus) {
        LOGGER.info("Create: The Factory Must WORK initializing");

        TFMGTweaksSoundEvents.init();

        TFMGTweaksBlocks.BLOCKS.register(modEventBus);
        TFMGTweaksItems.ITEMS.register(modEventBus);
        TFMGTweaksBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        TFMGTweaksFeatures.FEATURES.register(modEventBus);
        TFMGTweaksPlacementModifiers.PLACEMENT_MODIFIERS.register(modEventBus);
        TFMGTweaksFluids.FLUID_TYPES.register(modEventBus);
        TFMGTweaksFluids.FLUIDS.register(modEventBus);
        TFMGTweaksFluids.BLOCKS.register(modEventBus);

        modEventBus.addListener(this::buildCreativeModeTabContents);
        modEventBus.addListener(this::onRegister);

        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TFMGTweaksConfig.SPEC);
    }

    private void onRegister(RegisterEvent event) {
        if (event.getRegistry() == BuiltInRegistries.TRIGGER_TYPES) {
            TFMGTweaksTriggers.register();
        }
    }

    private void buildCreativeModeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() != TFMGCreativeTabs.TFMG_MAIN.get()) {
            return;
        }
        event.accept(TFMGTweaksItems.OIL_ROCK.get());
        event.accept(TFMGTweaksItems.STEAM_BUCKET.get());
        event.accept(TFMGTweaksItems.BURNING_FUEL_BUCKET.get());
    }
}
