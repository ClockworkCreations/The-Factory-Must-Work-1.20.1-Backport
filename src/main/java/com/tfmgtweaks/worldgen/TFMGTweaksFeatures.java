package com.tfmgtweaks.worldgen;

import com.tfmgtweaks.TFMGTweaks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public class TFMGTweaksFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, TFMGTweaks.MOD_ID);

    public static final RegistryObject<OilRockFeature> OIL_ROCK =
            FEATURES.register("oil_rock", () -> new OilRockFeature(NoneFeatureConfiguration.CODEC));
}
