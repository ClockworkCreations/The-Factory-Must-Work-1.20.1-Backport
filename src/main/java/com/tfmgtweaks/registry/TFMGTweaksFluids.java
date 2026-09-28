package com.tfmgtweaks.registry;

import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.content.fluid.BurningFuelBlock;
import com.tfmgtweaks.content.fluid.BurningFuelFlowingFluid;
import com.tfmgtweaks.content.fluid.BurningFuelFluidType;
import com.tfmgtweaks.content.fluid.SteamFluid;
import com.tfmgtweaks.content.fluid.SteamFluidType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.BaseFlowingFluid;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class TFMGTweaksFluids {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, TFMGTweaks.MOD_ID);

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, TFMGTweaks.MOD_ID);

    /** Local DeferredRegister<Block>, not shared with TFMGTweaksBlocks, to avoid a circular reference with this fluid's own block. */
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, TFMGTweaks.MOD_ID);

    public static final RegistryObject<FluidType> STEAM_TYPE = FLUID_TYPES.register("steam",
            () -> new SteamFluidType(FluidType.Properties.create()
                    .descriptionId("fluid.tfmgtweaks.steam")
                    .canSwim(false)
                    .canDrown(false)
                    .canPushEntity(false)
                    .canExtinguish(false)
                    .canConvertToSource(false)
                    .supportsBoating(false)
                    .density(-10)
                    .viscosity(200)
                    .lightLevel(0)
                    .temperature(400)));

    public static final RegistryObject<SteamFluid> STEAM_SOURCE =
            FLUIDS.register("steam", () -> SteamFluid.createSource(steamProperties()));

    public static final RegistryObject<SteamFluid> STEAM_FLOWING =
            FLUIDS.register("flowing_steam", () -> SteamFluid.createFlowing(steamProperties()));

    private static BaseFlowingFluid.Properties steamProperties() {
        return new BaseFlowingFluid.Properties(STEAM_TYPE::get, STEAM_SOURCE::get, STEAM_FLOWING::get)
                .bucket(() -> TFMGTweaksItems.STEAM_BUCKET.get());
    }

    /**
     * A real, placeable fluid unlike Steam (which never places a real
     * block) -- FluidIgnition.markBurning() physically places this in
     * the world. Has a bucket for scooping up existing burning fuel
     * (like a lava bucket), not for creating it from scratch.
     */
    public static final RegistryObject<FluidType> BURNING_FUEL_TYPE = FLUID_TYPES.register("burning_fuel",
            () -> new BurningFuelFluidType(FluidType.Properties.create()
                    .descriptionId("fluid.tfmgtweaks.burning_fuel")
                    .canSwim(false)
                    .canDrown(false)
                    .lightLevel(15)
                    .density(3000)
                    .viscosity(3000)
                    .temperature(1500)));

    public static final RegistryObject<BaseFlowingFluid.Source> BURNING_FUEL_SOURCE =
            FLUIDS.register("burning_fuel", () -> new BaseFlowingFluid.Source(burningFuelProperties()));

    public static final RegistryObject<BurningFuelFlowingFluid> BURNING_FUEL_FLOWING =
            FLUIDS.register("flowing_burning_fuel", () -> new BurningFuelFlowingFluid(burningFuelProperties()));

    /**
     * The missing piece from the first version of this fluid: no
     * registered block meant createLegacyBlock() had nothing valid to
     * build a BlockState from, so ignited fluid silently fell back to
     * air. Modeled on vanilla lava. Light level isn't set here -- that's
     * BurningFuelFluidType's lightLevel(15), which LiquidBlock already
     * delegates to.
     */
    public static final RegistryObject<BurningFuelBlock> BURNING_FUEL_BLOCK = BLOCKS.register("burning_fuel",
            () -> new BurningFuelBlock(BURNING_FUEL_SOURCE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.LAVA)));

    /**
     * slopeFindDistance/explosionResistance are missing here relative to
     * every one of TFMG's own fluids (all set 1-5 and 100f respectively)
     * -- confirmed as a real, reported gap: slopeFindDistance
     * specifically governs how far a fluid looks to find a downward
     * path, which affects the flow-direction computation the renderer
     * uses to orient/scroll the flowing texture. Without it, this fluid
     * was left on whatever NeoForge's own unconfigured default is,
     * rather than matching TFMG's own established value for a
     * comparable flammable fluid. 5 matches most of TFMG's own liquid
     * fuels (crude oil, diesel, gasoline, kerosene); only their much
     * more viscous molten metals use a lower value.
     */
    private static BaseFlowingFluid.Properties burningFuelProperties() {
        return new BaseFlowingFluid.Properties(BURNING_FUEL_TYPE::get, BURNING_FUEL_SOURCE::get, BURNING_FUEL_FLOWING::get)
                .bucket(() -> TFMGTweaksItems.BURNING_FUEL_BUCKET.get())
                .block(BURNING_FUEL_BLOCK::get)
                .slopeFindDistance(5)
                .levelDecreasePerBlock(1)
                .explosionResistance(100f);
    }
}
