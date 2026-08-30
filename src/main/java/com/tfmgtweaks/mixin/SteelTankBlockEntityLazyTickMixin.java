package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.decoration.tanks.steel.SteelTankBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A distillation tower stops detecting heat after a reload, since
 * lazyTick only calls updateTemperature() when isDistillationTower is
 * true, but that flag is only recomputed by updateBoilerState(), never
 * fired by a plain world load. Calls updateBoilerState() from lazyTick()
 * too, so state self-corrects periodically.
 */
@Mixin(SteelTankBlockEntity.class)
public abstract class SteelTankBlockEntityLazyTickMixin {

    @Inject(method = "lazyTick", at = @At("HEAD"))
    private void tfmgtweaks$reevaluateTowerStateOnLazyTick(CallbackInfo ci) {
        SteelTankBlockEntity self = (SteelTankBlockEntity) (Object) this;
        self.updateBoilerState();
    }
}
