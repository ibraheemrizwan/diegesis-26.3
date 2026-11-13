package com.pockyl.diegesis.api.client;

import net.minecraft.sounds.SoundEvent;

import com.pockyl.diegesis.DiegesisMod;

/**
 * The library's quiet station sounds, for client code ({@code StationView.playSound}). They are defined in
 * {@code assets/diegesis/sounds.json}; a resource pack can replace any of them.
 */
public final class DiegesisSounds {
    public static final SoundEvent PICK_UP = event("station.pick_up");
    public static final SoundEvent PUT_DOWN = event("station.put_down");
    public static final SoundEvent GRAB = event("station.grab");
    public static final SoundEvent SET_DOWN = event("station.set_down");
    public static final SoundEvent TOGGLE = event("station.toggle");
    public static final SoundEvent BUTTON = event("station.button");
    public static final SoundEvent DETENT = event("station.detent");
    public static final SoundEvent POUR = event("station.pour");
    public static final SoundEvent SLOSH = event("station.slosh");
    public static final SoundEvent BUBBLE = event("station.bubble");
    public static final SoundEvent SPARK = event("station.spark");
    public static final SoundEvent STEAM = event("station.steam");

    private DiegesisSounds() {
    }

    /** Client sounds need no registry entry: the sound manager finds them by name in sounds.json. */
    private static SoundEvent event(String name) {
        return SoundEvent.createVariableRangeEvent(DiegesisMod.id(name));
    }
}
