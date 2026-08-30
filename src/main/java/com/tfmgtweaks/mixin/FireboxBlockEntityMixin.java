package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.misc.firebox.FireboxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A firebox crashes on every login attempt near it, making the chunk
 * unplayable: lazyTick() gets a `controller` that can legitimately be
 * null right after a chunk loads, then calls canBurn(controller)
 * unconditionally, which dereferences it with no null check. Returns
 * false (the existing "can't burn" fallback) if controller is null.
 */
@Mixin(FireboxBlockEntity.class)
public abstract class FireboxBlockEntityMixin {

    @Inject(method = "canBurn", at = @At("HEAD"), cancellable = true)
    private void tfmgtweaks$guardNullController(FireboxBlockEntity controller, CallbackInfoReturnable<Boolean> cir) {
        if (controller == null) {
            cir.setReturnValue(false);
        }
    }
}
