package com.pockyl.diegesis.api.state;

import net.minecraft.resources.Identifier;

import java.util.function.Predicate;

/**
 * A tank of a station: a flask, a vial, a basin.
 *
 * @param capacity the most it holds, in millibuckets
 * @param accepts  which liquids may be poured in
 * @param pourRate the fastest a player may pour out of or into it, in millibuckets per second (checked by the server)
 * @param keep     the contents stay between uses (needs a {@link StationHost}); otherwise every use starts from
 *                 {@code initial}
 * @param initial  what a fresh tank holds
 */
public record TankSpec(int capacity, Predicate<Identifier> accepts, int pourRate, boolean keep, TankContents initial) {
    public TankSpec {
        if (capacity < 1) {
            throw new IllegalArgumentException("A tank needs a capacity, got " + capacity);
        }
        if (pourRate < 1) {
            throw new IllegalArgumentException("pourRate must be positive, got " + pourRate);
        }
    }

    /** An empty tank taking any liquid, poured at up to 1000 mB a second. */
    public static TankSpec of(int capacity) {
        return new TankSpec(capacity, liquid -> true, 1000, false, TankContents.EMPTY);
    }

    public TankSpec accepts(Predicate<Identifier> value) {
        return new TankSpec(capacity, value, pourRate, keep, initial);
    }

    public TankSpec pourRate(int millibucketsPerSecond) {
        return new TankSpec(capacity, accepts, millibucketsPerSecond, keep, initial);
    }

    public TankSpec kept() {
        return new TankSpec(capacity, accepts, pourRate, true, initial);
    }

    /** Starts full of (or partly filled with) one liquid. */
    public TankSpec filledWith(Identifier liquid, int amount) {
        return new TankSpec(capacity, accepts, pourRate, keep, TankContents.of(liquid, Math.min(amount, capacity)));
    }
}
