package com.pockyl.diegesis.impl.client.fx;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.client.LiquidLook;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.impl.client.anim.Easing;

/**
 * What the liquid in one tank node is doing on this client. Two layers, as real sloshing has: the whole surface rocks
 * (a damped spring whose pitch comes from the vessel's width and the liquid's depth, so a thin tube chatters and a wide
 * bowl swings slowly), and small waves run across it (a coarse height field, reflecting off the walls), raised by things
 * falling in, by jolts and by boiling. A calm tank costs nothing: the field sleeps and is not even allocated until
 * the first ripple. The level and colour follow the state smoothly, so pouring does not move them in steps.
 */
public final class LiquidSim {
    /** Cells along a side of the height field. */
    public static final int GRID = 12;
    private static final float STEP = 1.0F / 60.0F;
    private static final int MAX_STEPS = 3;
    /** Wave speed squared, in cells per step: under 0.5 for the scheme to stay stable. */
    private static final float WAVE = 0.19F;
    private static final float WAVE_DAMPING = 0.972F;
    private static final float SETTLE = 0.996F;
    private static final float ASLEEP = 0.004F;
    /** The highest wave, node pixels. */
    private static final float MAX_WAVE = 1.4F;
    /** Pixels per second squared that tip the surface by 45 degrees. */
    private static final float GRAVITY = 900.0F;
    /** Sets the rocking's pitch: a vessel 6 pixels wide, deep compared to that, rocks at about 11 radians a second. */
    private static final float PITCH = 231.0F;
    private static final float ROCK_DAMPING = 0.13F;
    /** How hard turning the vessel throws the liquid. */
    private static final float TURN_KICK = 0.55F;
    /** Acceleration (pixels per second squared) from which a jolt also raises waves at the wall. */
    private static final float JOLT = 160.0F;
    private static final float JOLT_INTERVAL = 0.07F;

    /** How many cells a side the surface reads trails on. */
    public static final int WAKE_CELLS = 16;
    /**
     * Trails are furrows on a sheet that turns with the liquid, so they stay where they were drawn in it and only fade: how many cells a side, and
     * how wide it is, in tanks: the corners of a tank must stay on it as it turns.
     */
    private static final int SHEET_CELLS = 64;
    private static final float SHEET_SPAN = 1.5F;
    private static final float WAKE_WIDTH = 0.07F;
    private static final float WAKE_MIN_RADIUS = 2.7F;
    /** What is left of a trail after its life. */
    private static final float WAKE_FADE = 3.0F;
    private static final float WAKE_GONE = 0.01F;
    /** Furrows run out sideways as they age: how often (seconds), how much at a time. */
    private static final float WAKE_BLUR_EVERY = 0.12F;
    private static final float WAKE_BLUR = 0.1F;
    /** The light on the furrows' sides comes from here across the tank; how much a side and how much the depth of a furrow shade it. */
    private static final float WAKE_LIGHT_X = -0.6F;
    private static final float WAKE_LIGHT_Z = -0.8F;
    private static final float WAKE_SIDE = 1.1F;
    private static final float WAKE_DEPTH = 0.22F;
    private static final float WAKE_MAX_SHADE = 0.5F;

    private final Vector3f lastPos = new Vector3f();
    private final Vector3f lastVelocity = new Vector3f();
    private final Vector3f lastAxis = new Vector3f(0.0F, 1.0F, 0.0F);
    private final Vector3f slosh = new Vector3f();
    private final Vector3f sloshSpeed = new Vector3f();
    private boolean primed;

    private float[] heights;
    private float[] speeds;
    private boolean rippling;
    private float stepDebt;
    private float joltClock;
    private float boilDebt;

    private float fraction;
    private final float[] shownColor = new float[4];
    private boolean colorSet;
    private int colorOverride;
    private float edgeBoilWanted;
    private float edgeBoil;
    private float clock;
    private float[] sheet;
    private float[] spareSheet;
    private float[] wakeHeights;
    private float[] wakeShades;
    private boolean wakeShown;
    private float wakeLife = 2.5F;
    private float wakeContrast = 0.6F;
    private float wakeBlurClock;
    private float mottleWanted;
    private float mottle;
    private float current;
    private float turned;
    private TankContents contents;
    private LiquidLook look;

    /**
     * Advances the liquid by {@code dt} seconds.
     *
     * @param matrix    the node's matrix in station space
     * @param inner     the tank's inner box, node pixels
     * @param contents  what the tank holds
     * @param capacity  the tank's capacity
     * @param agitation how restless the surface is, 0 to 1
     */
    public void update(float dt, Matrix4f matrix, Box inner, TankContents contents, int capacity, float agitation) {
        float wanted = capacity <= 0 ? 0.0F : Math.min(1.0F, (float) contents.amount() / capacity);
        if (contents != this.contents) {
            this.contents = contents;
            if (!contents.isEmpty()) {
                look = LiquidRenderer.look(contents);
            }
        }
        if (!primed || dt <= 0.0F) {
            matrix.getTranslation(lastPos);
            matrix.transformDirection(lastAxis.set(0.0F, 1.0F, 0.0F)).normalize();
            lastVelocity.zero();
            fraction = wanted;
            if (look != null) {
                setColor(shownTarget());
            }
            primed = true;
            return;
        }
        mottle = Easing.approach(mottle, mottleWanted, 0.3F, dt);
        edgeBoil = Easing.approach(edgeBoil, edgeBoilWanted, 0.5F, dt);
        clock += dt;
        turned += current * dt;
        fraction = Easing.approach(fraction, wanted, 0.09F, dt);
        if (Math.abs(fraction - wanted) < 0.0005F) {
            fraction = wanted;
        }
        if (look != null) {
            easeColor(shownTarget(), dt);
        }
        rock(dt, matrix, inner);
        boil(dt, agitation);
        updateWake(dt);
        if (rippling) {
            stepDebt = Math.min(stepDebt + dt, STEP * MAX_STEPS);
            while (stepDebt >= STEP) {
                stepDebt -= STEP;
                step();
            }
        }
    }

    private void rock(float dt, Matrix4f matrix, Box inner) {
        Vector3f pos = matrix.getTranslation(new Vector3f());
        Vector3f velocity = new Vector3f(pos).sub(lastPos).div(dt);
        Vector3f acceleration = new Vector3f(velocity).sub(lastVelocity).div(dt);
        Vector3f axis = matrix.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
        float turnX = (axis.x - lastAxis.x) / dt;
        float turnZ = (axis.z - lastAxis.z) / dt;
        lastPos.set(pos);
        lastVelocity.set(velocity);
        lastAxis.set(axis);

        float scale = matrix.getScale(new Vector3f()).y;
        float width = Math.max(0.5F, Math.max(inner.to().x() - inner.from().x(), inner.to().z() - inner.from().z()) * scale);
        float depth = (inner.to().y() - inner.from().y()) * fraction * scale;
        float omega = (float) Math.sqrt(Math.PI * PITCH / width * Math.tanh(Math.PI * depth / width));
        omega = Math.max(6.0F, Math.min(24.0F, omega));

        float targetX = Math.max(-0.7F, Math.min(0.7F, acceleration.x / GRAVITY));
        float targetZ = Math.max(-0.7F, Math.min(0.7F, acceleration.z / GRAVITY));
        float rockDamping = ROCK_DAMPING + 0.6F * viscosity();
        sloshSpeed.x += (omega * omega * (targetX - slosh.x) - 2.0F * rockDamping * omega * sloshSpeed.x + turnX * TURN_KICK * omega) * dt;
        sloshSpeed.z += (omega * omega * (targetZ - slosh.z) - 2.0F * rockDamping * omega * sloshSpeed.z + turnZ * TURN_KICK * omega) * dt;
        slosh.x = Math.max(-0.6F, Math.min(0.6F, slosh.x + sloshSpeed.x * dt));
        slosh.z = Math.max(-0.6F, Math.min(0.6F, slosh.z + sloshSpeed.z * dt));
        if (Math.abs(slosh.x) + Math.abs(slosh.z) + Math.abs(sloshSpeed.x) + Math.abs(sloshSpeed.z) < 1.0e-3F) {
            slosh.zero();
            sloshSpeed.zero();
        }

        // A jolt piles the liquid against the wall it is thrown at, and the wave runs back across.
        joltClock -= dt;
        float push = (float) Math.hypot(acceleration.x, acceleration.z);
        if (push > JOLT && joltClock <= 0.0F && fraction > 0.0F) {
            joltClock = JOLT_INTERVAL;
            impulse(0.5F - 0.45F * acceleration.x / push, 0.5F + 0.45F * acceleration.z / push, Math.min(0.5F, push / 2400.0F));
        }
    }

    /** Something moves through the liquid at {@code u, v}, 0 to 1 across the tank. */
    public void leaveWake(float u, float v) {
        leaveWake(u, v, WAKE_WIDTH, 1.0F);
    }

    /** The same for something of its own size: {@code width} a part of the tank's width, {@code strength} 0 to 1. */
    public void leaveWake(float u, float v, float width, float strength) {
        if (sheet == null) {
            sheet = new float[SHEET_CELLS * SHEET_CELLS];
            spareSheet = new float[SHEET_CELLS * SHEET_CELLS];
            wakeHeights = new float[(WAKE_CELLS + 2) * (WAKE_CELLS + 2)];
            wakeShades = new float[WAKE_CELLS * WAKE_CELLS];
        }
        float cos = (float) Math.cos(turned);
        float sin = (float) Math.sin(turned);
        float x = u - 0.5F;
        float z = v - 0.5F;
        float cx = sheetCell(x * cos + z * sin);
        float cz = sheetCell(z * cos - x * sin);
        float radius = Math.max(WAKE_MIN_RADIUS, width / SHEET_SPAN * SHEET_CELLS);
        int reach = (int) radius + 1;
        for (int row = Math.max(0, (int) cz - reach); row <= Math.min(SHEET_CELLS - 1, (int) cz + reach); row++) {
            for (int column = Math.max(0, (int) cx - reach); column <= Math.min(SHEET_CELLS - 1, (int) cx + reach); column++) {
                float dx = column + 0.5F - cx;
                float dz = row + 0.5F - cz;
                float near = 1.0F - (dx * dx + dz * dz) / (radius * radius);
                if (near > 0.0F) {
                    int cell = row * SHEET_CELLS + column;
                    sheet[cell] = Math.max(sheet[cell], strength * near);
                }
            }
        }
        wakeShown = true;
    }

    /** How long trails stay, in seconds, and how clearly they stand out, 0 to 1: thin water forgets a stir at once, a thick soup keeps it. */
    public void setWakeLook(float seconds, float contrast) {
        wakeLife = seconds;
        wakeContrast = contrast;
    }

    private static float sheetCell(float fromMiddle) {
        return (fromMiddle / SHEET_SPAN + 0.5F) * SHEET_CELLS;
    }

    /** The trails fade and run out, and the surface's cells get their shade: the sheet turns under them. */
    private void updateWake(float dt) {
        if (!wakeShown) {
            return;
        }
        float keep = (float) Math.exp(-dt * WAKE_FADE / Math.max(wakeLife, 0.05F));
        float highest = 0.0F;
        for (int i = 0; i < sheet.length; i++) {
            sheet[i] *= keep;
            highest = Math.max(highest, sheet[i]);
        }
        wakeShown = highest > WAKE_GONE;
        if (!wakeShown) {
            java.util.Arrays.fill(sheet, 0.0F);
            java.util.Arrays.fill(wakeShades, 0.0F);
            return;
        }
        wakeBlurClock -= dt;
        if (wakeBlurClock <= 0.0F) {
            wakeBlurClock = WAKE_BLUR_EVERY;
            blurWake();
        }
        shadeWake();
    }

    private void blurWake() {
        int n = SHEET_CELLS;
        for (int row = 1; row < n - 1; row++) {
            for (int column = 1; column < n - 1; column++) {
                int cell = row * n + column;
                float around = (sheet[cell - 1] + sheet[cell + 1] + sheet[cell - n] + sheet[cell + n]) / 4.0F;
                spareSheet[cell] = sheet[cell] + (around - sheet[cell]) * WAKE_BLUR;
            }
        }
        float[] blurred = spareSheet;
        spareSheet = sheet;
        sheet = blurred;
    }

    private void shadeWake() {
        float cos = (float) Math.cos(turned);
        float sin = (float) Math.sin(turned);
        int side = WAKE_CELLS + 2;
        for (int row = 0; row < side; row++) {
            float z = (row - 0.5F) / WAKE_CELLS - 0.5F;
            for (int column = 0; column < side; column++) {
                float x = (column - 0.5F) / WAKE_CELLS - 0.5F;
                wakeHeights[row * side + column] = sheetAt(sheetCell(x * cos + z * sin), sheetCell(z * cos - x * sin));
            }
        }
        for (int row = 0; row < WAKE_CELLS; row++) {
            for (int column = 0; column < WAKE_CELLS; column++) {
                int at = (row + 1) * side + column + 1;
                float slopeX = wakeHeights[at + 1] - wakeHeights[at - 1];
                float slopeZ = wakeHeights[at + side] - wakeHeights[at - side];
                float shade = (slopeX * WAKE_LIGHT_X + slopeZ * WAKE_LIGHT_Z) * WAKE_SIDE - wakeHeights[at] * WAKE_DEPTH;
                wakeShades[row * WAKE_CELLS + column] = Math.max(-WAKE_MAX_SHADE, Math.min(WAKE_MAX_SHADE, shade * wakeContrast));
            }
        }
    }

    /** The sheet between its cells. */
    private float sheetAt(float x, float z) {
        float cx = x - 0.5F;
        float cz = z - 0.5F;
        int column = (int) Math.floor(cx);
        int row = (int) Math.floor(cz);
        if (column < 0 || row < 0 || column >= SHEET_CELLS - 1 || row >= SHEET_CELLS - 1) {
            return 0.0F;
        }
        float fx = cx - column;
        float fz = cz - row;
        int cell = row * SHEET_CELLS + column;
        float near = sheet[cell] + (sheet[cell + 1] - sheet[cell]) * fx;
        float far = sheet[cell + SHEET_CELLS] + (sheet[cell + SHEET_CELLS + 1] - sheet[cell + SHEET_CELLS]) * fx;
        return near + (far - near) * fz;
    }

    public boolean hasWake() {
        return wakeShown;
    }

    /** How a cell of the surface is shaded by the trails, -1 to 1: darker in a furrow and on the side away from the light, lighter on the other. */
    public float wakeShade(int column, int row) {
        return wakeShades == null ? 0.0F : wakeShades[row * WAKE_CELLS + column];
    }

    /** How hard the liquid should seethe along the walls, 0 to 1; it gets there slowly, as heat does. */
    public void setEdgeBoil(float amount) {
        edgeBoilWanted = amount;
    }

    public float edgeBoil() {
        return edgeBoil;
    }

    /** Seconds this liquid has been shown, for what comes and goes on it. */
    public float clock() {
        return clock;
    }

    /** How uneven the surface looks should be, 0 to 1; it drifts there. */
    public void setMottle(float amount) {
        mottleWanted = amount;
    }

    public float mottle() {
        return mottle;
    }

    /** How fast the liquid turns, radians per second. */
    public void setCurrent(float radiansPerSecond) {
        current = radiansPerSecond;
    }

    /** How far the liquid has turned, radians. */
    public float turned() {
        return turned;
    }

    /** How thick the liquid moves, 0 to 1. */
    private float viscosity() {
        return look == null ? 0.0F : look.viscosity();
    }

    /** A boil throws up the surface here and there. */
    private void boil(float dt, float agitation) {
        if (agitation <= 0.0F || fraction <= 0.0F) {
            return;
        }
        boilDebt += dt * agitation * 26.0F;
        while (boilDebt >= 1.0F) {
            boilDebt -= 1.0F;
            impulse((float) Math.random() * 0.9F + 0.05F, (float) Math.random() * 0.9F + 0.05F, 0.1F + 0.28F * agitation);
        }
    }

    /**
     * Pushes the surface down at a point: {@code u, v} from 0 to 1 across the tank, {@code strength} about the height of
     * the wave in node pixels.
     */
    public void impulse(float u, float v, float strength) {
        if (heights == null) {
            heights = new float[GRID * GRID];
            speeds = new float[GRID * GRID];
        }
        float push = strength * 0.5F * (1.0F - 0.6F * viscosity());
        float cx = u * (GRID - 1);
        float cy = v * (GRID - 1);
        int x0 = Math.max(0, (int) cx - 2);
        int x1 = Math.min(GRID - 1, (int) cx + 3);
        int y0 = Math.max(0, (int) cy - 2);
        int y1 = Math.min(GRID - 1, (int) cy + 3);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx;
                float dy = y - cy;
                speeds[y * GRID + x] -= push * (float) Math.exp(-(dx * dx + dy * dy) / 2.2F);
            }
        }
        rippling = true;
    }

    private void step() {
        float highest = 0.0F;
        float thick = viscosity();
        float wave = WAVE * (1.0F - 0.55F * thick);
        float damping = WAVE_DAMPING - 0.07F * thick;
        for (int y = 0; y < GRID; y++) {
            int up = (y == 0 ? y : y - 1) * GRID;
            int down = (y == GRID - 1 ? y : y + 1) * GRID;
            int row = y * GRID;
            for (int x = 0; x < GRID; x++) {
                int left = x == 0 ? x : x - 1;
                int right = x == GRID - 1 ? x : x + 1;
                float here = heights[row + x];
                float pull = heights[row + left] + heights[row + right] + heights[up + x] + heights[down + x] - 4.0F * here;
                speeds[row + x] = (speeds[row + x] + pull * wave) * damping;
            }
        }
        float mean = 0.0F;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = (heights[i] + speeds[i]) * SETTLE;
            mean += heights[i];
        }
        // Waves move liquid about, they do not add any: what one place gains the others lose.
        mean /= heights.length;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = Math.max(-MAX_WAVE, Math.min(MAX_WAVE, heights[i] - mean));
            highest = Math.max(highest, Math.abs(heights[i]) + Math.abs(speeds[i]));
        }
        if (highest < ASLEEP) {
            rippling = false;
            java.util.Arrays.fill(heights, 0.0F);
            java.util.Arrays.fill(speeds, 0.0F);
        }
    }

    /** Whether waves are running; a calm surface can be drawn flat. */
    public boolean isRippling() {
        return rippling;
    }

    /** The wave's height at {@code u, v} (0 to 1 across the tank), node pixels. */
    public float height(float u, float v) {
        if (!rippling) {
            return 0.0F;
        }
        float fx = Math.max(0.0F, Math.min(1.0F, u)) * (GRID - 1);
        float fy = Math.max(0.0F, Math.min(1.0F, v)) * (GRID - 1);
        int x = Math.min((int) fx, GRID - 2);
        int y = Math.min((int) fy, GRID - 2);
        float tx = fx - x;
        float ty = fy - y;
        int at = y * GRID + x;
        float top = heights[at] + (heights[at + 1] - heights[at]) * tx;
        float bottom = heights[at + GRID] + (heights[at + GRID + 1] - heights[at + GRID]) * tx;
        return top + (bottom - top) * ty;
    }

    /** The surface's springy tilt, in station space (x and z; 0 when calm). */
    public Vector3fc slosh() {
        return slosh;
    }

    /** How full the tank looks now, 0 to 1. */
    public float fraction() {
        return fraction;
    }

    /** Draws the liquid in {@code argb} whatever it is made of (0: in its own colour again); it drifts there. */
    public void setColorOverride(int argb) {
        colorOverride = argb;
    }

    private int shownTarget() {
        return colorOverride != 0 ? colorOverride : look.color();
    }

    /** The look of what the tank holds (or held last); null while it never held anything. */
    public LiquidLook look() {
        return look;
    }

    /** The ARGB colour shown now: it drifts to a mixture's new colour. */
    public int color() {
        return Math.round(shownColor[0]) << 24 | Math.round(shownColor[1]) << 16 | Math.round(shownColor[2]) << 8 | Math.round(shownColor[3]);
    }

    private void setColor(int argb) {
        shownColor[0] = argb >>> 24;
        shownColor[1] = argb >> 16 & 0xFF;
        shownColor[2] = argb >> 8 & 0xFF;
        shownColor[3] = argb & 0xFF;
        colorSet = true;
    }

    private void easeColor(int argb, float dt) {
        if (!colorSet || fraction < 0.002F) {
            setColor(argb);
            return;
        }
        shownColor[0] = Easing.approach(shownColor[0], argb >>> 24, 0.18F, dt);
        shownColor[1] = Easing.approach(shownColor[1], argb >> 16 & 0xFF, 0.18F, dt);
        shownColor[2] = Easing.approach(shownColor[2], argb >> 8 & 0xFF, 0.18F, dt);
        shownColor[3] = Easing.approach(shownColor[3], argb & 0xFF, 0.18F, dt);
    }
}
