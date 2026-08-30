package com.tfmgtweaks.mixin;

import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reported symptom: hundreds of render-exception messages for a Create
 * fluid tank used as a native Boiler, not related to TFMG at all --
 * Create's own CTSpriteShiftEntry.getTargetU()/getTargetV() call
 * getTarget() with no null check, throwing during chunk rebuilds when
 * it's unpopulated. Falls back to the original unshifted UV coordinate
 * instead of crashing that block's render.
 */
@Mixin(CTSpriteShiftEntry.class)
public abstract class CTSpriteShiftEntryNullTargetFixMixin {

    @Inject(method = "getTargetU", at = @At("HEAD"), cancellable = true)
    private void tfmgtweaks$fallBackWhenTargetMissingU(float localU, int index, CallbackInfoReturnable<Float> cir) {
        CTSpriteShiftEntry self = (CTSpriteShiftEntry) (Object) this;
        if (self.getTarget() == null) {
            cir.setReturnValue(localU);
        }
    }

    @Inject(method = "getTargetV", at = @At("HEAD"), cancellable = true)
    private void tfmgtweaks$fallBackWhenTargetMissingV(float localV, int index, CallbackInfoReturnable<Float> cir) {
        CTSpriteShiftEntry self = (CTSpriteShiftEntry) (Object) this;
        if (self.getTarget() == null) {
            cir.setReturnValue(localV);
        }
    }
}
