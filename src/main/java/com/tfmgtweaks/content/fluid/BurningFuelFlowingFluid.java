package com.tfmgtweaks.content.fluid;

import com.tfmgtweaks.compat.TFMGTagKeys;
import com.tfmgtweaks.explosion.FluidIgnition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/** Burning fuel is placed via markBurning(); see it and FluidIgnition for the full lifecycle. */
public class BurningFuelFlowingFluid extends BaseFlowingFluid.Flowing {

    public BurningFuelFlowingFluid(BaseFlowingFluid.Properties properties) {
        super(properties);
    }

    /**
     * Without an adjacent source, vanilla dissipates a flowing fluid
     * back to air on its own schedule. A position already holding this
     * fluid is left as-is instead, since FluidIgnition manages its real
     * lifecycle directly; a brand new position still gets normal vanilla
     * computation, and isActivelyClearing() stops a cleared position
     * being refilled by a not-yet-reached burning neighbor.
     */
    @Override
    protected FluidState getNewLiquid(Level level, BlockPos pos, BlockState blockState) {
        if (level instanceof ServerLevel serverLevel && FluidIgnition.isActivelyClearing(serverLevel, pos)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        FluidState current = level.getFluidState(pos);
        if (current.getType() == this) {
            return current;
        }
        return super.getNewLiquid(level, pos, blockState);
    }

    /**
     * Refuses replacement by flammable fluid, mirroring WaterFluidMixin's
     * protection in reverse -- without it, fresh oil flowing downhill
     * could overwrite a just-ignited position before fire spread past it.
     * Not isSource()-restricted like that mixin, since burning fuel
     * spends most of its life as flowing fragments.
     */
    @Override
    public boolean canBeReplacedWith(FluidState fluidState, BlockGetter blockGetter, BlockPos pos, Fluid fluid,
                                      Direction direction) {
        if (fluid.is(TFMGTagKeys.FLAMMABLE_FLUID)) {
            return false;
        }
        return super.canBeReplacedWith(fluidState, blockGetter, pos, fluid, direction);
    }
}
