package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * Shows a station tank as liquid inside a node: it fills {@code inner} from the bottom, its surface stays level when the
 * node tilts and sloshes and ripples when the node is carried. With a {@code spout} the node pours: pointed at another
 * tank node that takes the liquid, it settles with its spout over the opening and tips until the liquid reaches the
 * spout, so a full vessel pours early and an emptying one tips further. The mouse wheel sets the flow.
 *
 * @param tank       the station tank
 * @param inner      the space the liquid fills, in the node's pixels
 * @param round      the liquid is a cylinder standing in {@code inner} instead of a box (the default, pixel-like)
 * @param spout      where the liquid leaves when poured, in the node's pixels; without one the node only receives
 * @param pourRange  how far (pixels) round another tank the cursor still counts as pointing at it
 * @param pourAngle  how far (degrees) the node tips at most; it stops earlier once the liquid reaches the spout
 * @param opening    where poured liquid goes in, in the node's pixels: the top of a flask's neck; the middle of the top of
 *                   {@code inner} without one
 * @param pourHeight how high (pixels) over the other tank's opening the spout is held, so the stream is in plain view
 */
public record TankBinding(String tank, Box inner, boolean round, Optional<Vector3fc> spout, float pourRange, float pourAngle,
        Optional<Vector3fc> opening, float pourHeight) {
    public static final float DEFAULT_POUR_RANGE = 2.5F;
    public static final float DEFAULT_POUR_ANGLE = 100.0F;
    public static final float DEFAULT_POUR_HEIGHT = 3.0F;

    public static final Codec<TankBinding> CODEC = StrictCodecs.record(instance -> instance.group(
            Codec.STRING.fieldOf("tank").forGetter(TankBinding::tank),
            Box.CODEC.fieldOf("inner").forGetter(TankBinding::inner),
            StrictCodecs.optional(Codec.BOOL, "round", false).forGetter(TankBinding::round),
            StrictCodecs.optional(StrictCodecs.VEC3, "spout").forGetter(binding -> binding.spout.map(Vector3f::new)),
            StrictCodecs.optional(Codec.floatRange(0.5F, 64.0F), "pour_range", DEFAULT_POUR_RANGE).forGetter(TankBinding::pourRange),
            StrictCodecs.optional(Codec.floatRange(0.0F, 135.0F), "pour_angle", DEFAULT_POUR_ANGLE).forGetter(TankBinding::pourAngle),
            StrictCodecs.optional(StrictCodecs.VEC3, "opening").forGetter(binding -> binding.opening.map(Vector3f::new)),
            StrictCodecs.optional(Codec.floatRange(0.0F, 32.0F), "pour_height", DEFAULT_POUR_HEIGHT).forGetter(TankBinding::pourHeight)
    ).apply(instance, (tank, inner, round, spout, range, angle, opening, height) -> new TankBinding(tank, inner, round,
            spout.map(vector -> (Vector3fc) vector), range, angle, opening.map(vector -> (Vector3fc) vector), height)));

    public TankBinding {
        spout = spout.map(Vector3f::new);
        opening = opening.map(Vector3f::new);
    }

    public static TankBinding of(String tank, Box inner) {
        return new TankBinding(tank, inner, false, Optional.empty(), DEFAULT_POUR_RANGE, DEFAULT_POUR_ANGLE, Optional.empty(),
                DEFAULT_POUR_HEIGHT);
    }

    public TankBinding withSpout(float x, float y, float z) {
        return new TankBinding(tank, inner, round, Optional.of(new Vector3f(x, y, z)), pourRange, pourAngle, opening, pourHeight);
    }

    /** Where poured liquid goes in, in the node's pixels: {@code opening}, or the middle of the top of {@code inner}. */
    public Vector3f mouth() {
        return opening.map(Vector3f::new).orElseGet(() -> new Vector3f((inner.from().x() + inner.to().x()) / 2.0F, inner.to().y(),
                (inner.from().z() + inner.to().z()) / 2.0F));
    }
}
