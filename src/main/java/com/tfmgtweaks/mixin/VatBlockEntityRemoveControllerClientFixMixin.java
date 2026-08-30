package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.vat.base.VatBlock;
import com.drmangotea.tfmg.content.machinery.vat.base.VatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * VatBlockEntity's own removeController() is a complete no-op on the
 * client (`if (level.isClientSide) return;` as its first line, before
 * even resetting controller/width/height). Since TFMG's own multiblock
 * formation runs independently on both sides, the client keeps
 * rendering the old merged structure even after a server-side split.
 * @Overwrite restructures it so the client-safe parts (resetting local
 * state, recomputing the blockstate) run unconditionally on both sides,
 * while server-authoritative parts stay gated as before.
 */
@Mixin(VatBlockEntity.class)
public abstract class VatBlockEntityRemoveControllerClientFixMixin {

    @Shadow
    protected BlockPos controller;

    @Shadow
    protected int width;

    @Shadow
    protected int height;

    @Shadow
    protected boolean window;

    @Shadow
    protected boolean updateConnectivity;

    @Shadow
    boolean evaluateNextTick;

    @Shadow
    public abstract void applyVatSize(int blocks);

    @Shadow
    protected abstract void onInventoryChanged();

    @Shadow
    private native void refreshCapability();

    @Overwrite
    public void removeController(boolean keepFluids) {
        VatBlockEntity self = (VatBlockEntity) (Object) this;
        boolean isClientSide = self.getLevel().isClientSide;

        controller = null;
        width = 1;
        height = 1;

        BlockState state = self.getBlockState();
        if (VatBlock.isVat(state)) {
            state = state.setValue(VatBlock.BOTTOM, true);
            state = state.setValue(VatBlock.TOP, true);
            state = state.setValue(VatBlock.SHAPE, window ? VatBlock.Shape.WINDOW : VatBlock.Shape.PLAIN);
            self.getLevel().setBlock(self.getBlockPos(), state, 22);
        }

        if (isClientSide) {
            return;
        }

        updateConnectivity = true;
        if (!keepFluids) {
            applyVatSize(1);
        }
        onInventoryChanged();

        evaluateNextTick = true;

        refreshCapability();
        self.setChanged();
        self.sendData();
    }
}
