package com.pockyl.diegesis.api.state;

import net.minecraft.util.Mth;

/**
 * A control of a station: a value the player sets with a knob, slider or toggle, or a button they press.
 *
 * @param isButton a button: it has no value, each press reaches the station logic
 * @param min     the lowest value
 * @param max     the highest value
 * @param steps   how many even steps lie between min and max (a toggle has 1); 0 for a smooth value
 * @param initial the value of a fresh station
 * @param isLocked players cannot change it, only the station logic ({@code state().setControl}): a status the scene
 *                shows (a burner that is lit, a progress gauge), synced like any control
 */
public record ControlSpec(boolean isButton, float min, float max, int steps, float initial, boolean isLocked) {
    public ControlSpec {
        if (!(max >= min) || steps < 0) {
            throw new IllegalArgumentException("Bad control range " + min + ".." + max + " / " + steps);
        }
    }

    /** A smooth value between min and max. */
    public static ControlSpec range(float min, float max, float initial) {
        return new ControlSpec(false, min, max, 0, initial, false);
    }

    /** A value between min and max in {@code steps} even steps. */
    public static ControlSpec stepped(float min, float max, int steps, float initial) {
        return new ControlSpec(false, min, max, steps, initial, false);
    }

    /** Off (0) or on (1). */
    public static ControlSpec toggle(boolean initiallyOn) {
        return new ControlSpec(false, 0.0F, 1.0F, 1, initiallyOn ? 1.0F : 0.0F, false);
    }

    public static ControlSpec button() {
        return new ControlSpec(true, 0.0F, 0.0F, 0, 0.0F, false);
    }

    /** The same control, but only the station logic may change it (see {@code isLocked}). */
    public ControlSpec locked() {
        return new ControlSpec(isButton, min, max, steps, initial, true);
    }

    /** {@code value} clamped to the range and snapped to the nearest step. */
    public float sanitize(float value) {
        if (!Float.isFinite(value)) {
            return initial;
        }
        float clamped = Mth.clamp(value, min, max);
        if (steps > 0 && max > min) {
            float step = (max - min) / steps;
            clamped = min + Math.round((clamped - min) / step) * step;
        }
        return clamped;
    }

    /** Where {@code value} lies in the range, 0 to 1. */
    public float fraction(float value) {
        return max > min ? Mth.clamp((value - min) / (max - min), 0.0F, 1.0F) : 0.0F;
    }

    /** The value at a position 0 to 1 of the range, snapped to a step. */
    public float valueAt(float fraction) {
        return sanitize(min + (max - min) * fraction);
    }
}
