package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * Makes a node shine on the scene: the objects around it get brighter the closer they are, also while it is carried
 * (a torch moved over the table), and a soft pool of light falls on the work surface under it. Code can change it
 * ({@code NodeView.setLight}).
 *
 * @param level   brightness like a block's light, 0-15
 * @param radius  how far it reaches, in pixels
 * @param color   ARGB colour of the pool of light on the surface (alpha: its strength; 0 draws none)
 * @param flicker how much the light wavers, 0 (a steady lamp) to 1; flames flicker a little by default
 * @param offset  where the light comes from in the node's own pixels (default its origin): the middle of a long lamp
 *                whose model is anchored at a corner
 */
public record LightDefinition(int level, float radius, int color, float flicker, Vector3fc offset) {
    public static final Codec<LightDefinition> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.intRange(0, 15), "level", 14).forGetter(LightDefinition::level),
            StrictCodecs.optional(Codec.floatRange(1.0F, 256.0F), "radius", 16.0F).forGetter(LightDefinition::radius),
            StrictCodecs.optional(Visual.COLOR, "color", 0x80FFD9A0).forGetter(LightDefinition::color),
            StrictCodecs.optional(Codec.floatRange(0.0F, 1.0F), "flicker", 0.12F).forGetter(LightDefinition::flicker),
            StrictCodecs.optional(StrictCodecs.VEC3, "offset", new Vector3f()).forGetter(light -> new Vector3f(light.offset))
    ).apply(instance, LightDefinition::new));

    public LightDefinition {
        offset = new Vector3f(offset);
    }

    /** A light of {@code level} reaching {@code radius} pixels, with the default pool colour and flicker. */
    public static LightDefinition of(int level, float radius) {
        return new LightDefinition(level, radius, 0x80FFD9A0, 0.12F, new Vector3f());
    }
}
