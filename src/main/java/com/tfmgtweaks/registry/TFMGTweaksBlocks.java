package com.tfmgtweaks.registry;

import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.content.oilrock.OilRockBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class TFMGTweaksBlocks {
    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TFMGTweaks.MOD_ID);

    public static final RegistryObject<OilRockBlock> OIL_ROCK = BLOCKS.register("oil_rock",
            () -> new OilRockBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .strength(2.5f, 6.0f)
                    .requiresCorrectToolForDrops()));
}
