package com.tfmgtweaks.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * Central home for TFMG-owned tags. Do not reference
 * TFMGTags.TFMGBlockTags/TFMGItemTags/TFMGFluidTags directly -- CE
 * renamed those enums, throwing NoClassDefFoundError against whichever
 * edition wasn't built against. Tag locations are data, confirmed
 * identical between editions, so building TagKeys directly sidesteps
 * needing an edition-specific variant per call site.
 */
public final class TFMGTagKeys {

    private TFMGTagKeys() {
    }

    /** tfmg:surface_scanner_findable -- vanilla enum entry: TFMGBlockTags.SURFACE_SCANNER_FINDABLE / CE: Blocks.SURFACE_SCANNER_FINDABLE */
    public static final TagKey<Block> SURFACE_SCANNER_FINDABLE = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tfmg", "surface_scanner_findable"));

    /** tfmg:flammable -- vanilla enum entry: TFMGFluidTags.FLAMMABLE / CE: Fluids.FLAMMABLE */
    public static final TagKey<Fluid> FLAMMABLE_FLUID = TagKey.create(
            Registries.FLUID, ResourceLocation.fromNamespaceAndPath("tfmg", "flammable"));
}
