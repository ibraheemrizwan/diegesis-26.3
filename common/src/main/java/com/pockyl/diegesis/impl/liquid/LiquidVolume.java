package com.pockyl.diegesis.impl.liquid;

/**
 * The level of a liquid in a tipped box: where a plane with a given "up" must lie so that the part of the box below it
 * holds a given share of the volume. Keeps the amount of liquid honest when a vessel tilts, and tells how far the
 * liquid has come up a spout. Pure maths, so a game test can check it.
 */
public final class LiquidVolume {
    /** Below this tilt a box's level is simply proportional. */
    private static final float UPRIGHT = 1.0e-4F;
    private static final int STEPS = 26;

    private LiquidVolume() {
    }

    /**
     * The level along {@code up}, measured from the box's centre, below which the box holds {@code fraction} of its volume.
     *
     * @param hx half the box's size along x, likewise {@code hy}, {@code hz}
     * @param ux the unit "up" in the box's space, likewise {@code uy}, {@code uz}
     */
    public static float level(float hx, float hy, float hz, float ux, float uy, float uz, float fraction) {
        float share = Math.max(0.0F, Math.min(1.0F, fraction));
        if (Math.abs(ux) < UPRIGHT && Math.abs(uz) < UPRIGHT) {
            return (2.0F * share - 1.0F) * hy * Math.signum(uy == 0.0F ? 1.0F : uy);
        }
        double floor = 1.0e-3 * Math.max(hx, Math.max(hy, hz));
        double a = Math.max(2.0 * hx * Math.abs(ux), floor);
        double b = Math.max(2.0 * hy * Math.abs(uy), floor);
        double c = Math.max(2.0 * hz * Math.abs(uz), floor);
        double low = 0.0;
        double high = a + b + c;
        for (int i = 0; i < STEPS; i++) {
            double middle = (low + high) / 2.0;
            if (shareBelow(middle, a, b, c) < share) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (float) ((low + high - a - b - c) / 2.0);
    }

    /** The share of the box below the plane at {@code level} (from the centre): the inverse of {@link #level}. */
    public static float shareBelow(float hx, float hy, float hz, float ux, float uy, float uz, float level) {
        double floor = 1.0e-3 * Math.max(hx, Math.max(hy, hz));
        double a = Math.max(2.0 * hx * Math.abs(ux), floor);
        double b = Math.max(2.0 * hy * Math.abs(uy), floor);
        double c = Math.max(2.0 * hz * Math.abs(uz), floor);
        return (float) shareBelow(level + (a + b + c) / 2.0, a, b, c);
    }

    /**
     * The corner cut off a box with edges {@code a, b, c} (edges measured along the plane's normal) by the plane at
     * {@code s} from its lowest corner, as a share of the box: inclusion-exclusion over the eight corners.
     */
    private static double shareBelow(double s, double a, double b, double c) {
        double sum = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            double past = s - ((corner & 1) != 0 ? a : 0.0) - ((corner & 2) != 0 ? b : 0.0) - ((corner & 4) != 0 ? c : 0.0);
            if (past > 0.0) {
                double cube = past * past * past;
                sum += Integer.bitCount(corner) % 2 == 0 ? cube : -cube;
            }
        }
        return Math.max(0.0, Math.min(1.0, sum / (6.0 * a * b * c)));
    }
}
