package com.tfmgtweaks.compat;

import com.drmangotea.tfmg.config.TFMGConfigs;
import com.drmangotea.tfmg.content.machinery.oil_processing.surface_scanner.SurfaceScannerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * SurfaceScannerBlockEntity#hasOil() changed signature between TFMG
 * builds: vanilla takes just a BlockPos, CE takes a pre-resolved
 * (ChunkAccess, BlockPos). Mixin injections need edition-specific
 * classes for this, but an ordinary method call from our own code (like
 * SurfaceScannerBlockEntityRescanThrottleMixin's signal grid) isn't
 * descriptor-checked, so it would throw NoSuchMethodError against the
 * other edition. Resolves the correct overload reflectively once.
 */
public final class SurfaceScannerCompat {

    private static volatile MethodHandle handle;
    private static volatile boolean resolved = false;

    private SurfaceScannerCompat() {
    }

    public static boolean hasOil(SurfaceScannerBlockEntity self, Level level, BlockPos pos) {
        if (level == null) {
            return false;
        }
        if (!resolved) {
            resolve();
        }
        try {
            if (TFMGEdition.isCommunityEdition()) {
                int scanDepth = TFMGConfigs.common().machines.surfaceScannerScanDepth.get();
                ChunkAccess chunk = level.getChunk(pos);
                BlockPos midpoint = new ChunkPos(pos).getMiddleBlockPosition(scanDepth).north().west();
                return (boolean) handle.invoke(self, chunk, midpoint);
            } else {
                return (boolean) handle.invoke(self, pos);
            }
        } catch (Throwable t) {
            throw new RuntimeException("tfmgtweaks: failed to invoke SurfaceScannerBlockEntity#hasOil reflectively", t);
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Method m = TFMGEdition.isCommunityEdition()
                    ? SurfaceScannerBlockEntity.class.getMethod("hasOil", ChunkAccess.class, BlockPos.class)
                    : SurfaceScannerBlockEntity.class.getMethod("hasOil", BlockPos.class);
            handle = lookup.unreflect(m);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(
                    "tfmgtweaks: could not resolve SurfaceScannerBlockEntity#hasOil for edition "
                            + TFMGEdition.current(), e);
        }
        resolved = true;
    }
}
