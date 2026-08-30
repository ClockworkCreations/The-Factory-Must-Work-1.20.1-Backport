package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.network.transformer.large.LargeTransformerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Connecting cables near a large transformer can crash-loop a
 * dedicated server permanently: resistance() guards with a broad
 * IElectric check, then calls getControlledBlock().getData(), but
 * getControlledBlock() does its own stricter check and can return null
 * -- and since resistance() runs every tick, that crash repeats
 * indefinitely. Bails out to 0 when getControlledBlock() is null.
 */
@Mixin(LargeTransformerBlockEntity.class)
public abstract class LargeTransformerBlockEntityMixin {

    @Inject(method = "resistance", at = @At("HEAD"), cancellable = true)
    private void tfmgtweaks$guardNullControlledBlock(CallbackInfoReturnable<Float> cir) {
        LargeTransformerBlockEntity self = (LargeTransformerBlockEntity) (Object) this;
        if (self.getControlledBlock() == null) {
            cir.setReturnValue(0f);
        }
    }
}
