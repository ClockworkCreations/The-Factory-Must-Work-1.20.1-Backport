package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.misc.flarestack.FlarestackBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same pattern as CastingBasinBlockEntityCapabilityFixMixin: tick()
 * drains gas every tick but never calls invalidateCapabilities(), so a
 * pipe that cached "full" once has no signal room opened back up --
 * gas can back up and stall production upstream, not just here.
 */
@Mixin(FlarestackBlockEntity.class)
public abstract class FlarestackBlockEntityCapabilityFixMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void tfmgtweaks$invalidateCapabilitiesOnTick(CallbackInfo ci) {
        FlarestackBlockEntity self = (FlarestackBlockEntity) (Object) this;
        if (self.getLevel() != null) {
            self.getLevel().invalidateCapabilities(self.getBlockPos());
        }
    }
}
