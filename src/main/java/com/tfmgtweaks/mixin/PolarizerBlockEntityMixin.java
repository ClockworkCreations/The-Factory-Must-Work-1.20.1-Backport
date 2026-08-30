package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.utilities.polarizer.PolarizerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bug: the Polarizer only reliably finishes a recipe with a steady load
 * (e.g. a lamp) also on the grid, since tick() gates both charging and
 * completion behind the same instantaneous getPowerUsage() >= 1000
 * check, and completion needs that check to pass one extra time after
 * reaching 100%. This TAIL @Inject forces completion if charge is
 * already full but that extra tick didn't land.
 */
@Mixin(PolarizerBlockEntity.class)
public abstract class PolarizerBlockEntityMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void tfmgtweaks$completeOnceFullyChargedRegardlessOfThisTicksPower(CallbackInfo ci) {
        PolarizerBlockEntity self = (PolarizerBlockEntity) (Object) this;
        if (self.chargeCapacitors && self.capacitorPercentage >= 200) {
            self.onInventoryChanged(self.inventory.getStackInSlot(0).getCount());
        }
    }
}
