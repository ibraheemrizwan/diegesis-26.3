package com.pockyl.diegesis.impl.client.anim;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A critically damped spring towards a moving target: smooth, never overshoots by itself, frame-rate independent.
 * Gives carried objects their weight (they trail the cursor a little) and lets them settle back gently.
 */
public final class Spring {
    private final Vector3f value = new Vector3f();
    private final Vector3f velocity = new Vector3f();
    private final Vector3f scratch = new Vector3f();
    private float halfLife;

    /** {@code halfLife}: seconds to close half the distance to the target. */
    public Spring(float halfLife) {
        this.halfLife = halfLife;
    }

    public void setHalfLife(float seconds) {
        this.halfLife = seconds;
    }

    public void snap(Vector3fc to) {
        value.set(to);
        velocity.zero();
    }

    /** Advances {@code dt} seconds towards {@code target}. */
    public Vector3fc update(Vector3fc target, float dt) {
        if (dt <= 0.0F) {
            return value;
        }
        // Closed-form critically damped spring, exact for any step length:
        // j0 = x - goal, j1 = v + j0 * y, x' = e^(-y dt) (j0 + j1 dt) + goal, v' = e^(-y dt) (v - j1 y dt).
        float y = (float) (2.0 * Math.log(2.0) / Math.max(halfLife, 1.0e-4F));
        float decay = (float) Math.exp(-y * dt);
        Vector3f j0 = scratch.set(value).sub(target);
        float j1x = velocity.x + j0.x * y;
        float j1y = velocity.y + j0.y * y;
        float j1z = velocity.z + j0.z * y;
        value.set(decay * (j0.x + j1x * dt) + target.x(), decay * (j0.y + j1y * dt) + target.y(), decay * (j0.z + j1z * dt) + target.z());
        velocity.set(decay * (velocity.x - j1x * y * dt), decay * (velocity.y - j1y * y * dt), decay * (velocity.z - j1z * y * dt));
        return value;
    }

    public Vector3fc value() {
        return value;
    }

    /** Pixels per second. */
    public Vector3fc velocity() {
        return velocity;
    }
}
