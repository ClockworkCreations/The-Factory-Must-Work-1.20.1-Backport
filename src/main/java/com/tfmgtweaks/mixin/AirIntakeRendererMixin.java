package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.machinery.misc.air_intake.AirIntakeRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * AirIntakeRenderer never overrides getViewDistance(), falling back to
 * vanilla's 64-block default -- easy to hit for a large multiblock, and
 * since its block model is empty (everything drawn via SuperByteBuffer),
 * going past that distance leaves a dark, unlit void rather than
 * disappearing cleanly. 128 matches TFMG's own LargeEngineRenderer
 * precedent for a comparable multiblock.
 */
@Mixin(AirIntakeRenderer.class)
public abstract class AirIntakeRendererMixin {

    public int getViewDistance() {
        return 128;
    }
}
