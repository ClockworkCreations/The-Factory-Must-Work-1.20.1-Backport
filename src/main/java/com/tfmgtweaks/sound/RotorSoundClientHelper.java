package com.tfmgtweaks.sound;

import com.drmangotea.tfmg.content.electricity.generators.large_generator.RotorBlockEntity;
import com.tfmgtweaks.TFMGTweaksSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Isolated from RotorBlockEntityMixin, which mixes into a common
 * (server-and-client) class and must never reference a client-only
 * type. This class holds every client-only reference instead, only
 * ever reached from the mixin's own isClientSide-guarded branch, so
 * it's never loaded on a dedicated server.
 */
public class RotorSoundClientHelper {

    private static final Map<BlockPos, RotorHumSoundInstance> ACTIVE = new HashMap<>();

    public static void tick(RotorBlockEntity rotor) {
        if (rotor.getSpeed() == 0) {
            return;
        }
        BlockPos pos = rotor.getBlockPos();
        RotorHumSoundInstance existing = ACTIVE.get(pos);
        if (existing != null && existing.isActivelyPlaying()) {
            // Already playing -- its own getVolume()/getPitch() are
            // queried fresh every tick by the sound engine itself, so
            // there's nothing further to do here for an already-active
            // instance.
            return;
        }
        RotorHumSoundInstance instance =
                new RotorHumSoundInstance(TFMGTweaksSoundEvents.GENERATOR_HUM.getMainEvent(), rotor);
        ACTIVE.put(pos, instance);
        Minecraft.getInstance().getSoundManager().play(instance);
    }
}
