package com.tfmgtweaks.mixin;

import com.simibubi.create.foundation.utility.DynamicComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's own DynamicComponent.resolve() has no null check on
 * parsedCustomText, unlike the class's own get() (which handles it
 * gracefully). Reported as a client disconnect when a TFMG segmented
 * display syncs. Returns "" for null, matching what get() already
 * returns for the same case -- protects every caller, not just displays.
 */
@Mixin(DynamicComponent.class)
public abstract class DynamicComponentResolveNullFixMixin {

    @Shadow
    private Component parsedCustomText;

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true, require = 0)
    private void tfmgtweaks$preventNullResolveCrash(CallbackInfoReturnable<String> cir) {
        if (parsedCustomText == null) {
            cir.setReturnValue("");
        }
    }
}
