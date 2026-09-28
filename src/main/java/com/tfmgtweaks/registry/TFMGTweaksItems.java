package com.tfmgtweaks.registry;

import com.tfmgtweaks.TFMGTweaks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class TFMGTweaksItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TFMGTweaks.MOD_ID);

    public static final RegistryObject<BlockItem> OIL_ROCK = ITEMS.register("oil_rock",
            () -> new BlockItem(TFMGTweaksBlocks.OIL_ROCK.get(), new Item.Properties()));

    public static final RegistryObject<BucketItem> STEAM_BUCKET = ITEMS.register("steam_bucket",
            () -> new BucketItem(TFMGTweaksFluids.STEAM_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    public static final RegistryObject<BucketItem> BURNING_FUEL_BUCKET = ITEMS.register("burning_fuel_bucket",
            () -> new BucketItem(TFMGTweaksFluids.BURNING_FUEL_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
}
