package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.utilities.traffic_light.TrafficLightBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TFMG's own light-color logic uses fixed transition widths (30/60
 * ticks) never scaled to the configured timer length -- at the default
 * 180-tick setting, the green condition becomes mathematically
 * unreachable (`timer < 60 && timer > 60`). Recomputes `light` after
 * tick() runs, with transition widths clamped to a safe fraction of the
 * half-cycle, matching the original result for any timer long enough
 * that the clamp never engages.
 */
@Mixin(TrafficLightBlockEntity.class)
public abstract class TrafficLightGreenPhaseFixMixin {

    @Shadow
    protected ScrollValueBehaviour timerLength;

    @Shadow
    int light;

    @Inject(method = "tick", at = @At("TAIL"))
    private void tfmgtweaks$fixGreenPhase(CallbackInfo ci) {
        TrafficLightBlockEntity self = (TrafficLightBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide) {
            return;
        }

        int halfTimer = timerLength.getValue() / 2;
        int transitionWindow = Math.min(30, halfTimer / 4);
        int finalTransitionWindow = Math.min(60, halfTimer / 4);

        if (self.timer < halfTimer - transitionWindow && self.timer > finalTransitionWindow) {
            light = 0;
        } else if (self.timer > halfTimer + transitionWindow) {
            light = 2;
        } else {
            light = 1;
        }
    }
}
