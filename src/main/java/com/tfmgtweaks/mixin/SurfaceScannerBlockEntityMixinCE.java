package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.oil_processing.surface_scanner.SurfaceScannerBlockEntity;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CE-only counterpart to SurfaceScannerBlockEntityMixin: CE changed
 * hasOil()'s signature from {@code hasOil(BlockPos)} to
 * {@code hasOil(ChunkAccess, BlockPos)}. The vanilla mixin's Sable
 * sub-level redirect isn't replicated here, since CE's own
 * findDeposits() already resolves the real-world position natively
 * before calling hasOil(). Only one of the two mixins applies at a
 * time, gated by TFMGTweaksMixinPlugin.
 */
@Mixin(SurfaceScannerBlockEntity.class)
public abstract class SurfaceScannerBlockEntityMixinCE {

    private static final TagKey<Block> SURFACE_SCANNER_FINDABLE_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tfmg", "surface_scanner_findable"));

    @Inject(method = "hasOil(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("TAIL"), cancellable = true)
    private void tfmgtweaks$scanOilRockRange(ChunkAccess chunk, BlockPos midpoint,
                                              CallbackInfoReturnable<Boolean> cir) {
        SurfaceScannerBlockEntity self = (SurfaceScannerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        if (tfmgtweaks$scanOilRockHeightRange(level, midpoint)) {
            cir.setReturnValue(true);
        }
    }

    private boolean tfmgtweaks$scanOilRockHeightRange(Level level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            return false;
        }

        int minY = Math.min(TFMGTweaksConfig.OIL_ROCK_MIN_HEIGHT.get(), TFMGTweaksConfig.OIL_ROCK_MAX_HEIGHT.get());
        int maxY = Math.max(TFMGTweaksConfig.OIL_ROCK_MIN_HEIGHT.get(), TFMGTweaksConfig.OIL_ROCK_MAX_HEIGHT.get());

        // Same strided sampling as the vanilla fix -- see
        // SurfaceScannerBlockEntityMixin for the reasoning.
        int stride = 3;
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        for (int x = minX; x <= minX + 15; x += stride) {
            for (int z = minZ; z <= minZ + 15; z += stride) {
                for (int y = minY; y <= maxY; y += stride) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(SURFACE_SCANNER_FINDABLE_TAG)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
