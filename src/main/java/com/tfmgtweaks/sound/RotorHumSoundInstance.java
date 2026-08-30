package com.tfmgtweaks.sound;

import com.drmangotea.tfmg.content.electricity.generators.large_generator.RotorBlockEntity;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * A genuine, continuous, looping sound for the rotor's hum, with
 * volume/pitch that scale with live speed each tick -- the standard
 * mechanism for this (Create's own ContinuousSound works the same way:
 * override getVolume()/getPitch() to compute fresh each call). Holds a
 * direct RotorBlockEntity reference since this class is entirely
 * client-only, constructed only from RotorSoundClientHelper.
 */
public class RotorHumSoundInstance extends AbstractTickableSoundInstance {

    private final RotorBlockEntity rotor;
    private final BlockPos pos;
    private boolean activelyPlaying = true;

    public RotorHumSoundInstance(SoundEvent event, RotorBlockEntity rotor) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.rotor = rotor;
        this.pos = rotor.getBlockPos().immutable();
        this.looping = true;
        this.delay = 0;
        this.relative = false;
    }

    /** False once this instance has stopped itself, so RotorSoundClientHelper knows a fresh instance is needed if the rotor spins up again. */
    public boolean isActivelyPlaying() {
        return activelyPlaying;
    }

    @Override
    public void tick() {
        if (!activelyPlaying) {
            return;
        }
        if (rotor.isRemoved() || rotor.getLevel() == null || rotor.getSpeed() == 0) {
            activelyPlaying = false;
            stop();
        }
    }

    @Override
    public float getVolume() {
        return Mth.clamp(Math.abs(rotor.getSpeed()) / 128f, 0.1f, 0.7f);
    }

    @Override
    public float getPitch() {
        return Mth.clamp((Math.abs(rotor.getSpeed()) / 256f) + 0.5f, 0.6f, 1.4f);
    }

    @Override
    public double getX() {
        return pos.getX() + 0.5;
    }

    @Override
    public double getY() {
        return pos.getY() + 0.5;
    }

    @Override
    public double getZ() {
        return pos.getZ() + 0.5;
    }
}
