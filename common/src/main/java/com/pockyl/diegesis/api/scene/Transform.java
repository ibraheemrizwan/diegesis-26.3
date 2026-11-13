package com.pockyl.diegesis.api.scene;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * Where a node sits relative to its parent, in Blockbench units: {@code pos} in pixels (16 = one block), {@code rotation}
 * in degrees, applied as yaw (y), then pitch (x), then roll (z); {@code scale} is a factor, one number or one per axis.
 */
public record Transform(Vector3fc pos, Vector3fc rotation, Vector3fc scale) {
    public static final Transform IDENTITY = new Transform(new Vector3f(), new Vector3f(), new Vector3f(1.0F));

    private static final Codec<Vector3f> SCALE = Codec.either(Codec.FLOAT, StrictCodecs.VEC3).xmap(
            either -> either.map(Vector3f::new, vector -> vector),
            vector -> vector.x == vector.y && vector.y == vector.z ? Either.left(vector.x) : Either.right(vector));

    /** The transform fields inlined into a node object: {@code "pos"}, {@code "rotation"}, {@code "scale"}. */
    public static final MapCodec<Transform> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            StrictCodecs.optional(StrictCodecs.VEC3, "pos", new Vector3f()).forGetter(t -> new Vector3f(t.pos)),
            StrictCodecs.optional(StrictCodecs.VEC3, "rotation", new Vector3f()).forGetter(t -> new Vector3f(t.rotation)),
            StrictCodecs.optional(SCALE, "scale", new Vector3f(1.0F)).forGetter(t -> new Vector3f(t.scale))
    ).apply(instance, Transform::new));

    public Transform {
        pos = new Vector3f(pos);
        rotation = new Vector3f(rotation);
        scale = new Vector3f(scale);
    }

    public static Transform at(float x, float y, float z) {
        return new Transform(new Vector3f(x, y, z), new Vector3f(), new Vector3f(1.0F));
    }

    public Transform withPos(Vector3fc newPos) {
        return new Transform(newPos, rotation, scale);
    }

    public Transform withRotation(float x, float y, float z) {
        return new Transform(pos, new Vector3f(x, y, z), scale);
    }

    public Transform withScale(float factor) {
        return new Transform(pos, rotation, new Vector3f(factor));
    }

    /** The rotation as a quaternion. */
    public Quaternionf quaternion() {
        return toQuaternion(rotation, new Quaternionf());
    }

    /** Converts Euler degrees ({@code [x, y, z]}, applied y, x, z) into {@code dest}. */
    public static Quaternionf toQuaternion(Vector3fc degrees, Quaternionf dest) {
        return dest.rotationYXZ((float) Math.toRadians(degrees.y()), (float) Math.toRadians(degrees.x()),
                (float) Math.toRadians(degrees.z()));
    }
}
