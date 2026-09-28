package com.tfmgtweaks.compat;

import net.minecraftforge.fml.ModList;

/**
 * The only place in this mod allowed to know Flowing Fluids' modid by
 * name. Unlike PollutionCompat, this never needs to call into that
 * mod's own classes -- only whether it's loaded matters, since it
 * changes our own fluid-support logic, not anything Flowing Fluids
 * itself exposes an API for.
 */
public final class FlowingFluidsCompat {

    private static final boolean LOADED = ModList.get().isLoaded("flowing_fluids");

    private FlowingFluidsCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }
}
