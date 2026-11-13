package com.pockyl.diegesis.impl.client.fx;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.function.BooleanSupplier;

/** A sound that goes on at a station for as long as it is wanted, gliding to the volume and pitch asked for and fading out at the end. */
public final class StationLoop extends AbstractTickableSoundInstance {
    /** How much of the way to what is wanted goes by in a tick. */
    private static final float GLIDE = 0.12F;
    private static final float SILENT = 0.004F;

    private final BooleanSupplier alive;
    private float wantedVolume;
    private float wantedPitch;

    public StationLoop(SoundEvent sound, double x, double y, double z, float pitch, BooleanSupplier alive) {
        super(sound, SoundSource.BLOCKS, RandomSource.create());
        this.alive = alive;
        this.x = x;
        this.y = y;
        this.z = z;
        this.looping = true;
        this.delay = 0;
        this.volume = SILENT;
        this.pitch = pitch;
        wantedPitch = pitch;
    }

    public void want(float newVolume, float newPitch) {
        wantedVolume = newVolume;
        wantedPitch = newPitch;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (!alive.getAsBoolean()) {
            wantedVolume = 0.0F;
        }
        volume += (wantedVolume - volume) * GLIDE;
        pitch += (wantedPitch - pitch) * GLIDE;
        if (wantedVolume <= 0.0F && volume < SILENT) {
            stop();
        }
    }
}
