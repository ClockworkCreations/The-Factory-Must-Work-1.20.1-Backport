package com.tfmgtweaks.vat;

import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * Same idea as VatInputOnlyItemWrapper but for fluids (see
 * VatBlockEntityCapabilityFixMixin): reports both tanks combined, but
 * fill() only writes to input and drain() only reads from output --
 * fully symmetric isolation. Without the drain() isolation, a naive
 * combined-handler search across both tanks together could reject
 * extraction whenever the other tank also held fluid.
 */
public class VatInputOnlyFluidWrapper implements IFluidHandler {

    private final IFluidHandler input;
    private final IFluidHandler output;
    private final CombinedTankWrapper combined;

    public VatInputOnlyFluidWrapper(IFluidHandler input, IFluidHandler output) {
        this.input = input;
        this.output = output;
        this.combined = new CombinedTankWrapper(input, output);
    }

    @Override
    public int getTanks() {
        return combined.getTanks();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return combined.getFluidInTank(tank);
    }

    @Override
    public int getTankCapacity(int tank) {
        return combined.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return combined.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        return input.fill(resource, action);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        return output.drain(resource, action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        return output.drain(maxDrain, action);
    }
}
