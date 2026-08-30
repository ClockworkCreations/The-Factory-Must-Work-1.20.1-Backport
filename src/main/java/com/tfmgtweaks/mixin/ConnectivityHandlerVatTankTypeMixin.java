package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.decoration.tanks.TFMGFluidTankBlockEntity;
import com.drmangotea.tfmg.content.machinery.vat.base.VatBlock;
import com.drmangotea.tfmg.content.machinery.vat.base.VatBlockEntity;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.tfmgtweaks.compat.VatBlockCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rejects a mismatched-type candidate before it's absorbed into a
 * forming vat/tank structure, rather than detecting and splitting a
 * mixed-type merge after the fact -- Create's own formation code has no
 * concept of vatType or tank block class and would just re-absorb a
 * mismatched neighbor again. No separate BlockEntityType per variant,
 * since that's resolved from saved NBT and would break same-type
 * merging in existing worlds without a migration.
 */
@Mixin(ConnectivityHandler.class)
public abstract class ConnectivityHandlerVatTankTypeMixin {

    private static String tfmgtweaks$currentVatType = null;
    private static Class<?> tfmgtweaks$currentTankClass = null;

    /** formMulti() is the entry point every formation search starts from; records which vat type or tank class originated it. */
    @Inject(method = "formMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", at = @At("HEAD"))
    private static void tfmgtweaks$trackFormationOrigin(BlockEntity be, CallbackInfo ci) {
        tfmgtweaks$currentVatType = null;
        tfmgtweaks$currentTankClass = null;
        if (be instanceof VatBlockEntity && be.getBlockState().getBlock() instanceof VatBlock originBlock) {
            tfmgtweaks$currentVatType = VatBlockCompat.getVatType(originBlock);
        } else if (be instanceof TFMGFluidTankBlockEntity) {
            tfmgtweaks$currentTankClass = be.getBlockState().getBlock().getClass();
        }
    }

    /**
     * partAt() is what every candidate position resolves through; nulls
     * the result if its vatType/class doesn't match what was recorded
     * for the current search. vatType is read via VatBlockCompat, not
     * direct field access, since CE changed its declared type from
     * String to ResourceLocation.
     */
    @Inject(method = "partAt", at = @At("RETURN"), cancellable = true)
    private static void tfmgtweaks$rejectMismatchedVatTankType(BlockEntityType<?> type, BlockGetter level, BlockPos pos,
                                                                 CallbackInfoReturnable<BlockEntity> cir) {
        BlockEntity result = cir.getReturnValue();
        if (result == null) {
            return;
        }
        if (tfmgtweaks$currentVatType != null && result instanceof VatBlockEntity) {
            VatBlock resultBlock = result.getBlockState().getBlock() instanceof VatBlock vb ? vb : null;
            if (resultBlock == null || !VatBlockCompat.getVatType(resultBlock).equals(tfmgtweaks$currentVatType)) {
                cir.setReturnValue(null);
            }
        } else if (tfmgtweaks$currentTankClass != null && result instanceof TFMGFluidTankBlockEntity) {
            if (result.getBlockState().getBlock().getClass() != tfmgtweaks$currentTankClass) {
                cir.setReturnValue(null);
            }
        }
    }
}
