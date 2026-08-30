package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.metallurgy.casting_basin.CastingBasinBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CastingBasinBlockEntity.tick() empties its tank when a recipe
 * finishes but never invalidates capabilities, so a pipe that cached
 * "tank full" never learns it emptied -- a casting basin stops
 * accepting input after the first recipe. Runs at HEAD, not TAIL, since
 * tick() has multiple early returns.
 */
@Mixin(CastingBasinBlockEntity.class)
public abstract class CastingBasinBlockEntityCapabilityFixMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void tfmgtweaks$invalidateCapabilitiesOnTick(CallbackInfo ci) {
        CastingBasinBlockEntity self = (CastingBasinBlockEntity) (Object) this;
        if (self.getLevel() != null) {
            self.getLevel().invalidateCapabilities(self.getBlockPos());
        }
    }
}
