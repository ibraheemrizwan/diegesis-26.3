package com.pockyl.diegesis.api.client;

import net.minecraft.nbt.CompoundTag;

/** Plays an effect sent by a station's logic ({@code StationContext.effect}) on this client. */
@FunctionalInterface
public interface EffectHandler {
    /**
     * @param view the station the effect belongs to
     * @param node the node it starts at, or {@code null} for the station's centre
     * @param data the parameters the logic sent
     */
    void play(StationView view, String node, CompoundTag data);
}
