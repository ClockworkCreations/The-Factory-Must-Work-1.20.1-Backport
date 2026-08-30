package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.metallurgy.blast_stove.BlastStoveBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Blast Stove stops functioning after a server restart until an
 * affected block is broken and replaced, since the updateConnectivity
 * flag it checks every tick is only ever set true from block-place/
 * neighbor-changed handlers, never on a plain world load. Forces a
 * refresh every lazyTick(), the same self-healing pattern used
 * elsewhere for the distillation tower/blast furnace/vat. Mixed into
 * FluidTankBlockEntity (scoped via instanceof), since Blast Stove has
 * no lazyTick() of its own to target directly.
 */
@Mixin(FluidTankBlockEntity.class)
public abstract class BlastStoveConnectivityRefreshMixin {

    @Inject(method = "lazyTick", at = @At("HEAD"))
    private void tfmgtweaks$refreshBlastStoveConnectivity(CallbackInfo ci) {
        if ((Object) this instanceof BlastStoveBlockEntity blastStove) {
            blastStove.updateConnectivity = true;
        }
    }
}
