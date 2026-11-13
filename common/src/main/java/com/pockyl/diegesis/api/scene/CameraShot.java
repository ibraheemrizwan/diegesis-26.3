package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * Another view of a scene for the camera ({@link CameraRig#inventory()}), in the same coordinates as the rig.
 *
 * @param position the camera's position
 * @param target   the point it looks at
 * @param fov      field of view in degrees; 0 keeps the rig's
 */
public record CameraShot(Vector3fc position, Vector3fc target, float fov) {
    public static final Codec<CameraShot> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.VEC3.fieldOf("position").forGetter(shot -> new Vector3f(shot.position)),
            StrictCodecs.VEC3.fieldOf("target").forGetter(shot -> new Vector3f(shot.target)),
            StrictCodecs.optional(Codec.floatRange(0.0F, 170.0F), "fov", 0.0F).forGetter(CameraShot::fov)
    ).apply(instance, CameraShot::new));

    public CameraShot {
        position = new Vector3f(position);
        target = new Vector3f(target);
    }
}
