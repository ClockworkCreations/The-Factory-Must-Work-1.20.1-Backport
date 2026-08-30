package com.tfmgtweaks.registry;

import com.tfmgtweaks.TFMGTweaks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TFMGTweaksItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TFMGTweaks.MOD_ID);

    public static final DeferredItem<BlockItem> OIL_ROCK = ITEMS.registerSimpleBlockItem(
            "oil_rock", TFMGTweaksBlocks.OIL_ROCK);

    /**
     * Steam stays non-placeable -- this bucket exists so players have a
     * real item to put into a fluid filter and request Steam extraction
     * with (Items.AIR, the original placeholder, can't be selected).
     * Matches TFMG's own convention for gas fluids exactly. Also needs a
     * Spout recipe to actually produce one -- see
     * data/tfmgtweaks/recipe/filling/steam_bucket.json.
     */
    public static final DeferredItem<BucketItem> STEAM_BUCKET = ITEMS.register("steam_bucket",
            () -> new BucketItem(TFMGTweaksFluids.STEAM_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    /**
     * Unlike STEAM_BUCKET above, burning_fuel is a real, placeable fluid
     * (see BurningFuelBlock) -- this is the standard vanilla bucket
     * mechanic, not a fluid-filter workaround: scoop a source block up
     * with an empty bucket, carry it safely, pour it back out somewhere
     * else. No custom logic needed at all; BucketItem already handles
     * pickup/placement for any fluid, and once placed it immediately
     * resumes normal behavior (particles, fire damage, spread) since all
     * of that is tied directly to the block/fluid itself, not any
     * separate placement-time setup.
     */
    public static final DeferredItem<BucketItem> BURNING_FUEL_BUCKET = ITEMS.register("burning_fuel_bucket",
            () -> new BucketItem(TFMGTweaksFluids.BURNING_FUEL_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
}
