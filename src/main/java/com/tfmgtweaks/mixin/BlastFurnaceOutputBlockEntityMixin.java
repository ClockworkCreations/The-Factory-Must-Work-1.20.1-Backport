package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.metallurgy.blast_furnace.BlastFurnaceOutputBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A fully reinforced blast furnace reverts to "regular" the moment an
 * item is pushed in, since isReinforced is only recomputed inside
 * getSize() when the input becomes non-empty -- if a wall position's
 * chunk hasn't loaded at that exact moment, it undercounts permanently.
 * Same self-healing pattern as SteelTankBlockEntityLazyTickMixin: also
 * calls getSize() from lazyTick().
 */
@Mixin(BlastFurnaceOutputBlockEntity.class)
public abstract class BlastFurnaceOutputBlockEntityMixin {

    @Inject(method = "lazyTick", at = @At("HEAD"))
    private void tfmgtweaks$reevaluateReinforcementOnLazyTick(CallbackInfo ci) {
        BlastFurnaceOutputBlockEntity self = (BlastFurnaceOutputBlockEntity) (Object) this;
        self.getSize();
    }
}
