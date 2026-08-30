package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.decoration.tanks.steel.SteelTankBlock;
import com.drmangotea.tfmg.content.decoration.tanks.steel.SteelTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SteelTankBlock.updateTowerState() calls getControllerBE() up to five
 * times without checking for null, which it legitimately can be -- e.g.
 * while a Create contraption carrying a distillation tower is being
 * assembled. One early-cancel guard covers all five unsafe call sites
 * at once, rather than patching each individually.
 */
@Mixin(SteelTankBlock.class)
public abstract class SteelTankBlockUpdateTowerStateMixin {

    @Inject(method = "updateTowerState", at = @At("HEAD"), cancellable = true)
    private static void tfmgtweaks$guardNullController(Level pLevel, BlockPos tankPos, boolean assemble,
                                                         boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        BlockEntity be = pLevel.getBlockEntity(tankPos);
        if (!(be instanceof SteelTankBlockEntity tankBE)) {
            return;
        }
        if (tankBE.getControllerBE() == null) {
            cir.setReturnValue(false);
        }
    }
}
