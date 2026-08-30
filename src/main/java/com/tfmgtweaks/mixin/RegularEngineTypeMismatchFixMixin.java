package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.engines.types.AbstractSmallEngineBlockEntity;
import com.drmangotea.tfmg.content.engines.types.regular_engine.RegularEngineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TFMG's own connect() chains adjacent engine blocks into one structure
 * with no check that engine type matches, so different engine types
 * (I/V/W/U/BOXER/RADIAL/TURBINE) can get chained together -- TFMG's own
 * source has this fix already written but commented out. Redirects
 * connect()'s scanning getBlockEntity() call to return null for a
 * type-mismatched candidate, letting the method's own existing "stop
 * and clean up" branch handle it naturally. Redundant on CE, which
 * fixed this differently (see TFMGTweaksMixinPlugin).
 */
@Mixin(AbstractSmallEngineBlockEntity.class)
public abstract class RegularEngineTypeMismatchFixMixin {

    @Redirect(method = "connect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity tfmgtweaks$hideTypeMismatchedEngine(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RegularEngineBlockEntity candidate
                && (Object) this instanceof RegularEngineBlockEntity self
                && candidate.type != self.type) {
            return null;
        }
        return be;
    }
}
