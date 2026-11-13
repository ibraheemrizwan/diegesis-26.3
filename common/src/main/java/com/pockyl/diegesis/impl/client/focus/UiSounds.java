package com.pockyl.diegesis.impl.client.focus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import com.pockyl.diegesis.api.client.DiegesisSounds;

/** The quiet foley of working at a station: picking up, putting down, levers and knobs. */
public final class UiSounds {
    private UiSounds() {
    }

    static void pickUp(Vec3 at) {
        play(DiegesisSounds.PICK_UP, at, 0.5F, 1.1F);
    }

    public static void putDown(Vec3 at) {
        play(DiegesisSounds.PUT_DOWN, at, 0.5F, 0.95F);
    }

    static void grab(Vec3 at) {
        play(DiegesisSounds.GRAB, at, 0.5F, 1.3F);
    }

    static void setDown(Vec3 at) {
        play(DiegesisSounds.SET_DOWN, at, 0.5F, 1.4F);
    }

    static void toggle(Vec3 at, boolean on) {
        play(DiegesisSounds.TOGGLE, at, 0.5F, on ? 0.65F : 0.55F);
    }

    static void button(Vec3 at) {
        play(DiegesisSounds.BUTTON, at, 0.5F, 0.9F);
    }

    static void detent(Vec3 at, float position) {
        play(DiegesisSounds.DETENT, at, 0.4F, 1.6F + position * 0.3F);
    }

    /** A vessel sounds higher the fuller it gets, as any filling bottle does. */
    static void pour(Vec3 at, float flow, float fill) {
        play(DiegesisSounds.POUR, at, 0.2F + 0.35F * flow, 0.95F + 0.55F * fill);
    }

    private static void play(SoundEvent sound, Vec3 at, float volume, float pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !FocusController.get().options().sounds()) {
            return;
        }
        minecraft.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.BLOCKS, volume, pitch,
                minecraft.level.getRandom(), at.x, at.y, at.z));
    }
}
