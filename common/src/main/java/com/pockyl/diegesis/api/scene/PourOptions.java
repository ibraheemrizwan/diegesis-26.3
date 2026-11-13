package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * How pouring looks in a scene; written as {@code "pouring"} in a scene file, every field optional.
 *
 * @param hints       while a vessel with liquid is in the hand, the tanks that would take it are marked at their openings
 * @param hintColor   ARGB colour of those marks
 * @param meter       a small bar next to the cursor shows how full the tank being poured into is
 * @param stream      the liquid runs in a continuous stream; false leaves only the drops
 * @param streamWidth how wide (pixels) the stream leaves the spout at full flow
 */
public record PourOptions(boolean hints, int hintColor, boolean meter, boolean stream, float streamWidth) {
    public static final PourOptions DEFAULT = new PourOptions(true, 0x40FFFFFF, true, true, 1.4F);

    public static final Codec<PourOptions> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.BOOL, "hints", DEFAULT.hints).forGetter(PourOptions::hints),
            StrictCodecs.optional(Visual.COLOR, "hint_color", DEFAULT.hintColor).forGetter(PourOptions::hintColor),
            StrictCodecs.optional(Codec.BOOL, "meter", DEFAULT.meter).forGetter(PourOptions::meter),
            StrictCodecs.optional(Codec.BOOL, "stream", DEFAULT.stream).forGetter(PourOptions::stream),
            StrictCodecs.optional(Codec.floatRange(0.05F, 8.0F), "stream_width", DEFAULT.streamWidth).forGetter(PourOptions::streamWidth)
    ).apply(instance, PourOptions::new));
}
