package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.generators.large_generator.RotorBlockEntity;
import com.tfmgtweaks.sound.RotorSoundClientHelper;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * See GeneratorBlockEntityMixin for why TFMG's rotor lost its ambient
 * sound. Uses a continuous, looping sound via RotorSoundClientHelper
 * (backed by RotorHumSoundInstance) rather than one-shot playback, so
 * volume/pitch track the rotor's live speed. Indirected through the
 * helper since RotorBlockEntity also loads on dedicated servers, and a
 * client-only type referenced directly here could risk a missing-class
 * error even behind the isClientSide check.
 */
@Mixin(RotorBlockEntity.class)
public abstract class RotorBlockEntityMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void tfmgtweaks$playGeneratorHum(CallbackInfo ci) {
        RotorBlockEntity self = (RotorBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || !level.isClientSide) {
            return;
        }
        RotorSoundClientHelper.tick(self);
    }
}
