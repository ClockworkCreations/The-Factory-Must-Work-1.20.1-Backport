package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.config.TFMGConfigs;
import com.drmangotea.tfmg.content.machinery.oil_processing.surface_scanner.SurfaceScannerBlockEntity;
import com.tfmgtweaks.compat.SableIntegration;
import com.tfmgtweaks.config.TFMGTweaksConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TFMG's own hasOil() only checks a single fixed Y level, so it can
 * never find Oil Rock, which spawns across a configurable range --
 * falls back to scanning that range (strided, not exhaustive) if the
 * original check finds nothing. Also resolves a Sable sub-level's local
 * position to the real world one before scanning, since hasOil()
 * otherwise checks the wrong coordinates there.
 */
@Mixin(SurfaceScannerBlockEntity.class)
public abstract class SurfaceScannerBlockEntityMixin {

    private static final TagKey<Block> SURFACE_SCANNER_FINDABLE_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tfmg", "surface_scanner_findable"));

    @Inject(method = "hasOil", at = @At("HEAD"), cancellable = true)
    private void tfmgtweaks$scanFromSableSubLevel(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        SurfaceScannerBlockEntity self = (SurfaceScannerBlockEntity) (Object) this;
        BlockPos worldPos = SableIntegration.resolveWorldPosition(self, pos);
        if (worldPos == null) {
            return;
        }
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        // Sable's own compatibility goal: getLevel() reports the real world
        // even on a sub-level, so we scan `level` at the transformed
        // world position instead of the sub-level-local one.
        cir.setReturnValue(tfmgtweaks$scanBothRanges(level, worldPos));
    }

    @Inject(method = "hasOil", at = @At("TAIL"), cancellable = true)
    private void tfmgtweaks$scanOilRockRange(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        SurfaceScannerBlockEntity self = (SurfaceScannerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        if (tfmgtweaks$scanOilRockHeightRange(level, pos)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Used only from the Sable redirect path, since that path bypasses
     * the original method (and therefore TFMG's own single-level check)
     * entirely -- replicates that check plus our own range fallback
     * against an arbitrary level/position pair.
     */
    private boolean tfmgtweaks$scanBothRanges(Level level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            // Never force-load/generate a chunk just to scan it.
            return false;
        }
        ChunkAccess chunk = level.getChunk(pos);
        int scanDepth = TFMGConfigs.common().machines.surfaceScannerScanDepth.get();
        AABB originalArea = new AABB(chunk.getPos().getMiddleBlockPosition(scanDepth).north().west())
                .inflate(7, 0, 7);
        for (BlockState state : chunk.getBlockStates(originalArea).toList()) {
            if (state.is(SURFACE_SCANNER_FINDABLE_TAG)) {
                return true;
            }
        }
        return tfmgtweaks$scanOilRockHeightRange(level, pos);
    }

    private boolean tfmgtweaks$scanOilRockHeightRange(Level level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            return false;
        }

        int minY = Math.min(TFMGTweaksConfig.OIL_ROCK_MIN_HEIGHT.get(), TFMGTweaksConfig.OIL_ROCK_MAX_HEIGHT.get());
        int maxY = Math.max(TFMGTweaksConfig.OIL_ROCK_MIN_HEIGHT.get(), TFMGTweaksConfig.OIL_ROCK_MAX_HEIGHT.get());

        // Strided sampling (every 3rd block) rather than exhaustive --
        // an Oil Rock cluster is at least 14 connected blocks, so a
        // stride of 3 reliably still intersects it while cutting the
        // check count by roughly 27x.
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
