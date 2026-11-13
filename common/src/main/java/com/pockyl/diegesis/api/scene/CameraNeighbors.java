package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * Going over to a station next to this one without leaving ({@link CameraRig#neighbors()}): the cursor resting at the edge
 * of the screen on the side another station stands flies the camera over to it, and the player works there. Both
 * scenes must allow it. The edges a glance or the inventory drawer uses are left to them, and nothing moves while a
 * stack is on the cursor or an object is in the hand.
 *
 * @param enabled   whether neighbours are reachable from this station, and this station from theirs
 * @param range     how far another station may stand, blocks along the floor (one up or down counts too)
 * @param zone      how far from the edge the cursor counts as being at it, as a part of the screen
 * @param delay     seconds the cursor must rest there, so that passing by moves nothing
 * @param time      seconds the camera takes to fly over
 * @param hint      draw a small arrow at an edge that leads to a neighbour
 * @param hintColor ARGB colour of the arrow; its alpha is the strongest it gets, with the cursor at the edge
 * @param keys      the movement keys and the arrow keys go over at once, to the neighbour on their side: left and right
 *                  (A, D), up to the top edge, down to the bottom one (W, S); same conditions as the edge
 */
public record CameraNeighbors(boolean enabled, int range, float zone, float delay, float time, boolean hint, int hintColor, boolean keys) {
    /**
     * Owner, 2026-11-08: stations standing side by side are one kitchen, the camera goes along it; A, D and the arrows
     * go along it too.
     */
    public static final CameraNeighbors DEFAULT = new CameraNeighbors(true, 3, 0.03F, 0.3F, 0.65F, true, 0xD0FFFFFF, true);
    public static final CameraNeighbors NONE = new CameraNeighbors(false, DEFAULT.range, DEFAULT.zone, DEFAULT.delay, DEFAULT.time,
            DEFAULT.hint, DEFAULT.hintColor, DEFAULT.keys);

    /** Without {@code keys}: they go over, as by default. */
    public CameraNeighbors(boolean enabled, int range, float zone, float delay, float time, boolean hint, int hintColor) {
        this(enabled, range, zone, delay, time, hint, hintColor, true);
    }

    public static final Codec<CameraNeighbors> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.BOOL, "enabled", DEFAULT.enabled).forGetter(CameraNeighbors::enabled),
            StrictCodecs.optional(Codec.intRange(1, 8), "range", DEFAULT.range).forGetter(CameraNeighbors::range),
            StrictCodecs.optional(Codec.floatRange(0.005F, 0.3F), "zone", DEFAULT.zone).forGetter(CameraNeighbors::zone),
            StrictCodecs.optional(Codec.floatRange(0.0F, 2.0F), "delay", DEFAULT.delay).forGetter(CameraNeighbors::delay),
            StrictCodecs.optional(Codec.floatRange(0.05F, 5.0F), "time", DEFAULT.time).forGetter(CameraNeighbors::time),
            StrictCodecs.optional(Codec.BOOL, "hint", DEFAULT.hint).forGetter(CameraNeighbors::hint),
            StrictCodecs.optional(Visual.COLOR, "hint_color", DEFAULT.hintColor).forGetter(CameraNeighbors::hintColor),
            StrictCodecs.optional(Codec.BOOL, "keys", DEFAULT.keys).forGetter(CameraNeighbors::keys)
    ).apply(instance, CameraNeighbors::new));
}
