package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.vat.base.VatBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Vat sometimes refuses a valid recipe intermittently, since
 * evaluate() (scans for attached machines) is gated by a one-shot flag
 * consumed on the first tick -- if that runs before every neighboring
 * machine's chunk is loaded, it permanently misses whatever wasn't
 * loaded yet. Calls evaluate() periodically from lazyTick() instead,
 * the same self-healing pattern used elsewhere.
 */
@Mixin(VatBlockEntity.class)
public abstract class VatBlockEntityMixin {

    @Inject(method = "lazyTick", at = @At("HEAD"))
    private void tfmgtweaks$reevaluateOnLazyTick(CallbackInfo ci) {
        VatBlockEntity self = (VatBlockEntity) (Object) this;
        self.evaluate();
    }
}
