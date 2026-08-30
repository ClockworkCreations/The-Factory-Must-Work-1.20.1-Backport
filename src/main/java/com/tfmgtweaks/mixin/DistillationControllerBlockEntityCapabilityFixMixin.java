package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.oil_processing.distillation_tower.controller.DistillationControllerBlockEntity;
import com.drmangotea.tfmg.content.machinery.oil_processing.distillation_tower.output.DistillationOutputBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Same pattern as CastingBasinBlockEntityCapabilityFixMixin: distillation
 * outputs occasionally stop pulling/pushing fluid until reformed, since
 * manageRecipe() drains its own tank and fills each output's tank
 * without invalidating either. Both invalidated here, per output, since
 * a pipe could be connected to any one of them.
 */
@Mixin(DistillationControllerBlockEntity.class)
public abstract class DistillationControllerBlockEntityCapabilityFixMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void tfmgtweaks$invalidateCapabilitiesOnTick(CallbackInfo ci) {
        DistillationControllerBlockEntity self = (DistillationControllerBlockEntity) (Object) this;
        if (self.getLevel() == null) {
            return;
        }
        self.getLevel().invalidateCapabilities(self.getBlockPos());
        ArrayList<DistillationOutputBlockEntity> outputs = self.getOutputs();
        if (outputs != null) {
            for (DistillationOutputBlockEntity output : outputs) {
                self.getLevel().invalidateCapabilities(output.getBlockPos());
            }
        }
    }
}
