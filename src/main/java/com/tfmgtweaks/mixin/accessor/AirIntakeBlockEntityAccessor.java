package com.tfmgtweaks.mixin.accessor;

import com.drmangotea.tfmg.content.machinery.misc.air_intake.AirIntakeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * getPossibleDiameter() (fixed via AirIntakeBlockEntityMixin) needs to
 * both read and write diameter/isController/isUsedByController on OTHER
 * AirIntakeBlockEntity instances, not just the one it's called on --
 * exactly the case @Shadow (which only ever resolves to "this") can't
 * cover, and exactly what an @Accessor interface is for instead: any
 * AirIntakeBlockEntity instance can be cast to this interface at
 * runtime, regardless of which specific object it is, since Mixin
 * injects the interface into the target class's own implements list.
 */
@Mixin(AirIntakeBlockEntity.class)
public interface AirIntakeBlockEntityAccessor {

    @Accessor("diameter")
    int tfmgtweaks$getDiameter();

    @Accessor("diameter")
    void tfmgtweaks$setDiameter(int value);

    @Accessor("isController")
    boolean tfmgtweaks$isController();

    @Accessor("isController")
    void tfmgtweaks$setIsController(boolean value);

    @Accessor("isUsedByController")
    boolean tfmgtweaks$isUsedByController();

    @Accessor("isUsedByController")
    void tfmgtweaks$setIsUsedByController(boolean value);
}
