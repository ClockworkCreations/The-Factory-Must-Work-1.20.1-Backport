package com.tfmgtweaks.compat;

import net.neoforged.fml.loading.LoadingModList;

/**
 * The only place in this mod allowed to know Flowing Fluids' modid by
 * name. Unlike PollutionCompat, this never needs to call into that
 * mod's own classes -- only whether it's loaded matters, since it
 * changes our own fluid-support logic, not anything Flowing Fluids
 * itself exposes an API for.
 */
public final class FlowingFluidsCompat {

    private static final boolean LOADED = LoadingModList.get().getModFileById("flowing_fluids") != null;

    private FlowingFluidsCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }
}
