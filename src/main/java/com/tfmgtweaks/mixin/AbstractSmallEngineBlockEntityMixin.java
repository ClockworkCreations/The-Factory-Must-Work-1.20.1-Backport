package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.engines.types.AbstractSmallEngineBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Engines crash while a Create contraption carrying them is being
 * assembled: hasTwoShafts() calls getValue(ENGINE_STATE) on a
 * neighboring segment without checking it still has that property --
 * during assembly that position can legitimately be air for a tick, and
 * getValue() throws rather than returning null. Redirects it to check
 * hasProperty() first, correctly falling through to "not a valid second
 * shaft" instead.
 */
@Mixin(AbstractSmallEngineBlockEntity.class)
public abstract class AbstractSmallEngineBlockEntityMixin {

    @Inject(method = "hasTwoShafts", at = @At("HEAD"), cancellable = true)
    private void tfmgtweaks$guardNullController(CallbackInfoReturnable<Boolean> cir) {
        AbstractSmallEngineBlockEntity self = (AbstractSmallEngineBlockEntity) (Object) this;
        if (!self.isController() && self.getControllerBE() == null) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
        method = "hasTwoShafts",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;"
                + "getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"))
    private Comparable<?> tfmgtweaks$safeGetEngineStateProperty(BlockState state, Property<?> property) {
        return state.hasProperty(property) ? state.getValue(property) : null;
    }
}
