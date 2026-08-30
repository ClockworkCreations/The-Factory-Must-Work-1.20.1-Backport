package com.tfmgtweaks.advancement;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Registered directly via a plain vanilla Registry.register() call (not
 * NeoForge's own DeferredRegister), matching Create's own AllTriggers --
 * confirmed as a real, working pattern for this exact registry type.
 * register() is called once from TFMGTweaks' own constructor.
 */
public class TFMGTweaksTriggers {

    public static final TFMGTweaksSimpleTrigger TANK_EXPLODED = new TFMGTweaksSimpleTrigger("tank_exploded");
    public static final TFMGTweaksSimpleTrigger FRACKED_OIL = new TFMGTweaksSimpleTrigger("fracked_oil");

    public static void register() {
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, TANK_EXPLODED.getId(), TANK_EXPLODED);
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, FRACKED_OIL.getId(), FRACKED_OIL);
    }
}
