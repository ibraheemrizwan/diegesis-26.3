package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * A plane of its own a grabbable object moves in instead of over the work surface: a wall between a rack of vials and
 * a flask, so moving the mouse up lifts the vial and moving it sideways carries it across, and it never drags over the
 * table. The object's grip follows the cursor on the plane, kept inside {@code bounds}.
 *
 * @param point  a point of the plane, in scene pixels
 * @param normal the plane's facing; {@code [0, 0, -1]} (the default) is an upright wall facing the front of the block
 * @param bounds where on the plane the grip may go (scene pixels); anywhere when empty
 */
public record GrabPlane(Vector3fc point, Vector3fc normal, Optional<Box> bounds) {
    public static final Codec<GrabPlane> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.VEC3.fieldOf("point").forGetter(plane -> new Vector3f(plane.point)),
            StrictCodecs.optional(StrictCodecs.VEC3, "normal", new Vector3f(0.0F, 0.0F, -1.0F)).forGetter(plane -> new Vector3f(plane.normal)),
            StrictCodecs.optional(Box.CODEC, "bounds").forGetter(GrabPlane::bounds)
    ).apply(instance, GrabPlane::new));

    public GrabPlane {
        point = new Vector3f(point);
        normal = new Vector3f(normal).normalize();
        if (!Float.isFinite(normal.x())) {
            throw new IllegalArgumentException("A grab plane's normal must not be zero");
        }
    }

    /** Where a ray (station space) meets the plane, kept in the bounds; null when it runs along or away from it. */
    public Vector3f intersect(Vector3fc origin, Vector3fc direction) {
        float facing = direction.dot(normal);
        if (Math.abs(facing) < 1.0e-5F) {
            return null;
        }
        float t = new Vector3f(point).sub(origin).dot(normal) / facing;
        if (t < 0.0F) {
            return null;
        }
        Vector3f hit = new Vector3f(direction).mul(t).add(origin);
        bounds.ifPresent(box -> {
            hit.x = Math.max(Math.min(box.from().x(), box.to().x()), Math.min(Math.max(box.from().x(), box.to().x()), hit.x));
            hit.y = Math.max(Math.min(box.from().y(), box.to().y()), Math.min(Math.max(box.from().y(), box.to().y()), hit.y));
            hit.z = Math.max(Math.min(box.from().z(), box.to().z()), Math.min(Math.max(box.from().z(), box.to().z()), hit.z));
        });
        return hit;
    }
}
