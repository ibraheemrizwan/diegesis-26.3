package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * Makes a node give off particles in the scene: steam over a flask, sparks from a burner. Code can switch it on and off
 * ({@code NodeView.setEmitting}).
 *
 * @param particle a particle style: the library's ({@code diegesis:spark}, {@code bubble}, {@code droplet}, {@code splash},
 *                 {@code steam}, {@code smoke}, {@code glint}, {@code dust}) or one registered by a mod
 * @param rate     particles per second
 * @param offset   where they start, in the node's pixels
 * @param velocity their start velocity in scene pixels per second; its direction turns with the node
 * @param spread   random spread of the start position, in pixels
 * @param color    an ARGB tint; the style's own colour without one
 * @param enabled  whether it emits from the start
 * @param scale    how much larger (or smaller) than the style's size the particles are
 * @param inLiquid the particles start anywhere in the node's liquid (its {@code tank}) and end at the surface - bubbles;
 *                 {@code offset} and {@code spread} are then unused
 */
public record EmitterDefinition(Identifier particle, float rate, Vector3fc offset, Vector3fc velocity, float spread,
        Optional<Integer> color, boolean enabled, float scale, boolean inLiquid) {
    public static final Codec<EmitterDefinition> CODEC = StrictCodecs.record(instance -> instance.group(
            Identifier.CODEC.fieldOf("particle").forGetter(EmitterDefinition::particle),
            StrictCodecs.optional(Codec.floatRange(0.0F, 200.0F), "rate", 4.0F).forGetter(EmitterDefinition::rate),
            StrictCodecs.optional(StrictCodecs.VEC3, "offset", new Vector3f()).forGetter(e -> new Vector3f(e.offset)),
            StrictCodecs.optional(StrictCodecs.VEC3, "velocity", new Vector3f()).forGetter(e -> new Vector3f(e.velocity)),
            StrictCodecs.optional(Codec.floatRange(0.0F, 64.0F), "spread", 0.5F).forGetter(EmitterDefinition::spread),
            StrictCodecs.optional(Visual.COLOR, "color").forGetter(EmitterDefinition::color),
            StrictCodecs.optional(Codec.BOOL, "enabled", true).forGetter(EmitterDefinition::enabled),
            StrictCodecs.optional(Codec.floatRange(0.05F, 20.0F), "scale", 1.0F).forGetter(EmitterDefinition::scale),
            StrictCodecs.optional(Codec.BOOL, "in_liquid", false).forGetter(EmitterDefinition::inLiquid)
    ).apply(instance, EmitterDefinition::new));

    public EmitterDefinition {
        offset = new Vector3f(offset);
        velocity = new Vector3f(velocity);
    }
}
