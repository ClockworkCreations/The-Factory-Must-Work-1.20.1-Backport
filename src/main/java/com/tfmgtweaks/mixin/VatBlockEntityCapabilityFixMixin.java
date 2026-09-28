package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.vat.base.VatBlockEntity;
import com.tfmgtweaks.vat.VatInputOnlyFluidWrapper;
import com.tfmgtweaks.vat.VatInputOnlyItemWrapper;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chemical vats: a full input blocks output, since VatBlockEntity
 * exposes a plain combined capability with no concept of which side
 * external insertion should go into -- once input fills, a hopper/pump
 * spills into output too, leaving no empty slot for recipe completion.
 * Two wrapper classes route insert/fill to the input side only.
 * tfmgtweaks$invalidateCapabilitiesOnTick fixes a related gap:
 * handleRecipe() writes new output but never invalidates capabilities,
 * so a pipe that cached "nothing here" never learns output arrived.
 */
@Mixin(VatBlockEntity.class)
public abstract class VatBlockEntityCapabilityFixMixin {

    @Inject(method = "getNewItemCapability", at = @At("RETURN"), cancellable = true)
    private void tfmgtweaks$restrictItemInsertToInput(CallbackInfoReturnable<IItemHandlerModifiable> cir) {
        VatBlockEntity self = (VatBlockEntity) (Object) this;
        if (self.isController()) {
            cir.setReturnValue(new VatInputOnlyItemWrapper(self.inputInventory, self.outputInventory));
        }
    }

    @Inject(method = "getNewFluidCapability", at = @At("RETURN"), cancellable = true)
    private void tfmgtweaks$restrictFluidFillToInput(CallbackInfoReturnable<IFluidHandler> cir) {
        VatBlockEntity self = (VatBlockEntity) (Object) this;
        if (!self.isController()) {
            return;
        }
        IFluidHandler inputHandler = self.inputTank.getCapability();
        IFluidHandler outputHandler = self.outputTank.getCapability();
        if (inputHandler == null || outputHandler == null) {
            return;
        }
        cir.setReturnValue(new VatInputOnlyFluidWrapper(inputHandler, outputHandler));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tfmgtweaks$invalidateCapabilitiesOnTick(CallbackInfo ci) {
        VatBlockEntity self = (VatBlockEntity) (Object) this;
        if (self.isController() && self.getLevel() != null) {
            self.getLevel().invalidateCapabilities(self.getBlockPos());
        }
    }
}
