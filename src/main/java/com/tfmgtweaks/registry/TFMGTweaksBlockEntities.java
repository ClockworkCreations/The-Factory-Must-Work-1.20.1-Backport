package com.tfmgtweaks.registry;

import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.content.oilrock.OilRockBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public class TFMGTweaksBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TFMGTweaks.MOD_ID);

    public static final RegistryObject<BlockEntityType<OilRockBlockEntity>> OIL_ROCK =
            BLOCK_ENTITIES.register("oil_rock", () -> BlockEntityType.Builder.of(
                    OilRockBlockEntity::new, TFMGTweaksBlocks.OIL_ROCK.get()).build(null));
}
