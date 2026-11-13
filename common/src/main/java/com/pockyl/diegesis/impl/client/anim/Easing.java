package com.pockyl.diegesis.impl.client.anim;

import net.minecraft.util.Mth;

/** Easing curves for 0..1 progress values. */
public final class Easing {
    private Easing() {
    }

    /** Slow start, slow end: camera flights. */
    public static float inOutCubic(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3.0) / 2.0F;
    }

    public static float outCubic(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    /** Moves {@code current} towards {@code target} exponentially: {@code rate} is the fraction closed per second. */
    public static float approach(float current, float target, float halfLife, float dt) {
        if (halfLife <= 0.0F) {
            return target;
        }
        float k = (float) Math.pow(0.5, dt / halfLife);
        return target + (current - target) * k;
    }
}
