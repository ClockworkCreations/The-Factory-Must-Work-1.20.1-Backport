package com.tfmgtweaks.content.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.function.Consumer;

/**
 * A dedicated fluid this mod owns outright, used to physically replace
 * ignited flammable fluid (see FluidIgnition.markBurning()) -- exists
 * because dynamically overriding the original fluid's light emission
 * via mixin proved unreliable, unlike a genuine static blockstate
 * property. move() deals fire damage directly (like TFMG's own
 * HotFluidType does for molten metals), avoiding the stale-tracking
 * problem a separate tick-loop scan had after a world reload.
 */
public class BurningFuelFluidType extends FluidType {

    private static final ResourceLocation STILL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tfmgtweaks", "block/burning_fuel_still");
    private static final ResourceLocation FLOWING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tfmgtweaks", "block/burning_fuel_flow");

    /** Matches FluidIgnition's own FIRE_TICKS_ON_CONTACT; kept as a separate local constant since that class no longer needs it. */
    private static final int FIRE_TICKS_ON_CONTACT = 60;

    public BurningFuelFluidType(Properties properties) {
        super(properties);
    }

    @Override
    public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
        consumer.accept(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return STILL_TEXTURE;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return FLOWING_TEXTURE;
            }

            @Override
            public int getTintColor(FluidStack stack) {
                return -1;
            }

            @Override
            public int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
                return -1;
            }
        });
    }

    @Override
    public boolean move(FluidState state, LivingEntity entity, Vec3 movementVector, double gravity) {
        // Math.max(), not a plain set -- called every tick this entity
        // is moving while inside the fluid, so a plain set would already
        // keep topping this up continuously regardless, but max() is the
        // same small extra safety FluidIgnition's own version already
        // had: never accidentally shorten fire ticks the entity already
        // has from some other, unrelated source.
        entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), FIRE_TICKS_ON_CONTACT));
        return false;
    }

    @Override
    public boolean canExtinguish(Entity entity) {
        // Explicit, not just relying on the default -- this fluid's
        // entire purpose is setting things on fire, so it should never
        // accidentally put out a fire it (or anything else) already
        // started, the same explicit choice TFMG's own HotFluidType
        // already makes for its molten metals.
        return false;
    }
}
