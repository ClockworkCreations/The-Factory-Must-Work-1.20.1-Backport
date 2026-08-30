package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.metallurgy.blast_stove.BlastStoveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A 3x3 Blast Stove renders with only its corners visible -- traced to
 * Create's own "window" system for FluidTankBlockEntity (its parent)
 * making center/edge segments see-through, the same way a 3-wide tank
 * shows fluid level. Blast Stove wasn't designed for 3x3 (TFMG's own
 * class has an unused, dead MAX_SIZE=2 constant), so this caps it at
 * 2x2, which also self-heals any existing broken 3x3.
 */
@Mixin(BlastStoveBlockEntity.class)
public abstract class BlastStoveBlockEntityMixin {

    public int getMaxWidth() {
        return 2;
    }
}
