package com.pockyl.diegesis.impl.client.anim;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/** An eased move of three numbers from one value to another over a duration. */
public final class Tween {
    private final Vector3f from = new Vector3f();
    private final Vector3f to = new Vector3f();
    private final Vector3f current = new Vector3f();
    private float elapsed;
    private float duration;
    private float arc;

    public Tween(float x, float y, float z) {
        from.set(x, y, z);
        to.set(x, y, z);
        current.set(x, y, z);
    }

    /** Starts moving from the current value to {@code target} over {@code seconds} (0 = jump). */
    public void start(float x, float y, float z, float seconds) {
        start(x, y, z, seconds, 0.0F);
    }

    /** The same on a hop: {@code height} over the straight way in y at the middle. */
    public void start(float x, float y, float z, float seconds, float height) {
        arc = height;
        from.set(current);
        to.set(x, y, z);
        elapsed = 0.0F;
        duration = Math.max(seconds, 0.0F);
        if (duration == 0.0F) {
            current.set(to);
        }
    }

    /** Whether it has not arrived yet. */
    public boolean isRunning() {
        return elapsed < duration;
    }

    public Vector3fc update(float dt) {
        if (elapsed < duration) {
            elapsed = Math.min(elapsed + dt, duration);
            float t = Easing.inOutCubic(elapsed / duration);
            from.lerp(to, t, current);
            current.y += arc * (float) Math.sin(Math.PI * elapsed / duration);
        }
        return current;
    }

    public Vector3fc value() {
        return current;
    }

    public boolean isMoving() {
        return elapsed < duration;
    }
}
