package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * What the player gets at a station is seen going into the inventory (owner: "we got the soup: show that we got it"): a stack that
 * comes into the inventory while the player works there, other than from their own cursor, flies from the station to its slot,
 * which bounces as it lands. When the drawer does not show that slot (a {@code compact} one), the stack stands next to the drawer
 * for a moment. Its count shows as "+N" (owner: "write +1, or more"): rising from the slot, or beside the stack by the drawer.
 * Written as {@code "gains"} in a scene file; every field is optional.
 *
 * @param enabled whether gains are shown
 * @param time    seconds a stack flies
 * @param hold    seconds a stack the drawer does not show stands next to it
 * @param sound   a soft pop as it lands
 * @param count   "+N" shows how many came
 */
public record GainOptions(boolean enabled, float time, float hold, boolean sound, boolean count) {
    public static final GainOptions DEFAULT = new GainOptions(true, 0.45F, 1.6F, true, true);
    public static final GainOptions OFF = new GainOptions(false, DEFAULT.time, DEFAULT.hold, DEFAULT.sound, DEFAULT.count);

    public static final Codec<GainOptions> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.BOOL, "enabled", DEFAULT.enabled).forGetter(GainOptions::enabled),
            StrictCodecs.optional(Codec.floatRange(0.05F, 3.0F), "time", DEFAULT.time).forGetter(GainOptions::time),
            StrictCodecs.optional(Codec.floatRange(0.0F, 10.0F), "hold", DEFAULT.hold).forGetter(GainOptions::hold),
            StrictCodecs.optional(Codec.BOOL, "sound", DEFAULT.sound).forGetter(GainOptions::sound),
            StrictCodecs.optional(Codec.BOOL, "count", DEFAULT.count).forGetter(GainOptions::count)
    ).apply(instance, GainOptions::new));
}
