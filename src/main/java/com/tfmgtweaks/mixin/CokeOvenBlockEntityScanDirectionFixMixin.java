package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.metallurgy.coke_oven.CokeOvenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Placing a second Coke Oven facing an existing one shuts the first off
 * unless they're far apart, since updateOvenBlocks() scans in the
 * oven's own facing direction while createMultiblock() always extends
 * in the opposite direction -- the one inconsistent usage in this
 * class. Redirects that scan to use facing.getOpposite(), matching
 * every other use of this pattern.
 */
@Mixin(CokeOvenBlockEntity.class)
public abstract class CokeOvenBlockEntityScanDirectionFixMixin {

    @Redirect(method = "updateOvenBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;I)Lnet/minecraft/core/BlockPos;"))
    private BlockPos tfmgtweaks$scanOppositeFacingNotFacing(BlockPos pos, Direction facing, int steps) {
        return pos.relative(facing.getOpposite(), steps);
    }
}
