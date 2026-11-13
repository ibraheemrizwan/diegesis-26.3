package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * Keeps the view of the station free while a player works at it: other players, mobs, dropped items and world particles between
 * the camera and the station dissolve, softly towards the edge of a cone round the line of sight, and come back as the camera
 * leaves. Nothing changes for anybody else. Written as {@code "clear_view"} in a scene file; every field is optional.
 *
 * @param entities  entities dissolve
 * @param particles the world's particles (a campfire's smoke) dissolve; the scene's own never do
 * @param radius    how wide the clear cone is at the station, in pixels
 * @param soft      how far beyond {@code radius} things come back to full strength, in pixels
 */
public record ClearViewOptions(boolean entities, boolean particles, float radius, float soft) {
    public static final ClearViewOptions DEFAULT = new ClearViewOptions(true, true, 16.0F, 12.0F);
    public static final ClearViewOptions OFF = new ClearViewOptions(false, false, 0.0F, 1.0F);

    public static final Codec<ClearViewOptions> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.BOOL, "entities", DEFAULT.entities).forGetter(ClearViewOptions::entities),
            StrictCodecs.optional(Codec.BOOL, "particles", DEFAULT.particles).forGetter(ClearViewOptions::particles),
            StrictCodecs.optional(Codec.floatRange(0.0F, 256.0F), "radius", DEFAULT.radius).forGetter(ClearViewOptions::radius),
            StrictCodecs.optional(Codec.floatRange(1.0F, 256.0F), "soft", DEFAULT.soft).forGetter(ClearViewOptions::soft)
    ).apply(instance, ClearViewOptions::new));
}
