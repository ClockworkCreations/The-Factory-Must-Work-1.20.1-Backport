package com.tfmgtweaks.integration.pollution;

import java.util.function.Supplier;

import net.neoforged.fml.loading.LoadingModList;

/**
 * The only place in this mod allowed to know Pollution of the Realms'
 * modid by name -- everything else routes through executeIfInstalled(),
 * modeled on Create's own optional-mod pattern. toExecute is a
 * Supplier<Runnable>, not a plain Runnable, so the actual code
 * referencing that mod's classes is only ever resolved inside the
 * isLoaded() branch, keeping this genuinely optional.
 */
public final class PollutionCompat {

    private static final boolean LOADED = LoadingModList.get().getModFileById("adpother") != null;

    private PollutionCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    /** Runs toExecute.get().run() only if Pollution of the Realms is loaded. */
    public static void executeIfInstalled(Supplier<Runnable> toExecute) {
        if (LOADED) {
            toExecute.get().run();
        }
    }
}
