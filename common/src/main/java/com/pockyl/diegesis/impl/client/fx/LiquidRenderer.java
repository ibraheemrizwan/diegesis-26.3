package com.pockyl.diegesis.impl.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.client.LiquidLook;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.impl.client.ClientRegistry;
import com.pockyl.diegesis.impl.liquid.LiquidVolume;

/**
 * Liquid inside a tank node, as a real volume: the tank's inner prism (a box, or a cylinder for {@code round} tanks) cut
 * by the surface plane, which lies where the prism holds the right share of its volume - tipping a vessel moves the
 * liquid, it never gains or loses any. Seen from outside the walls show through to the far ones, deepening with the
 * depth below the surface; the surface reflects the sky at a grazing angle and catches a highlight on its waves, and
 * a bright line runs where it meets the glass. Everything is vertex colour in the block atlas' translucent type, so it
 * sorts with the glass round it and works under shader packs.
 */
public final class LiquidRenderer {
    private static final int SEGMENTS = 14;
    /** The most corners a polygon can have after cutting: a cap of a round tank plus the two cut points. */
    private static final int MAX_POINTS = SEGMENTS + 4;
    /** Points round the surface while waves run. */
    private static final int WAVE_POINTS = 24;
    private static final float[] CALM_RINGS = {0.0F, 0.86F, 1.0F};
    private static final float[] WAVE_RINGS = {0.0F, 0.22F, 0.44F, 0.64F, 0.8F, 0.92F, 1.0F};
    private static final int MAX_VERTICES = WAVE_POINTS * 7;
    private static final float SKY_R = 0.85F;
    private static final float SKY_G = 0.93F;
    private static final float SKY_B = 1.0F;
    /**
     * How much liquid the eye looks through, in units of the look's alpha: seen through a wall at the surface and deep down,
     * the far wall the same, the surface. They add up: a wall in front of a far wall gives the liquid its full colour.
     */
    private static final float NEAR_PATH = 0.6F;
    private static final float NEAR_PATH_DEEP = 0.7F;
    private static final float FAR_PATH = 0.35F;
    private static final float FAR_PATH_DEEP = 0.35F;
    private static final float SURFACE_PATH = 0.85F;
    /** The sprite covers this many node pixels, or the whole tank if that is bigger. */
    private static final float SPRITE_SIZE = 16.0F;
    /** Sprite coordinates stay this far inside its edge: at the very edge the atlas' next sprite shows through in specks. */
    private static final float SPRITE_INSET = 0.02F;
    /** An uneven surface is one sheet of cells, so nothing has to sort against it: how many along a side, how level it must lie. */
    private static final int MOTTLE_CELLS = LiquidSim.WAKE_CELLS;
    private static final float MOTTLE_UPRIGHT = 0.985F;
    /** The patches: how big, and how many steps of shade, for the look of a texture rather than of a blur. */
    private static final float MOTTLE_SCALE = 0.6F;
    private static final float MOTTLE_DETAIL = 2.2F;
    private static final float MOTTLE_STEPS = 7.0F;
    private static final float MOTTLE_STRENGTH = 0.24F;
    /** Foam along the walls: how many cells deep it reaches, how often a cell changes (per second), the chance of foam by the wall and one further in. */
    private static final int SEETHE_CELLS = 2;
    private static final float SEETHE_BEAT = 5.0F;
    private static final float SEETHE_AT_WALL = 0.5F;
    private static final float SEETHE_INSIDE = 0.2F;
    private static final float SEETHE_LIGHT = 0.32F;
    /** A trail shades the liquid's own colour, in steps, so it stays pixels and stays food; only its lit side has a little gloss. */
    private static final float WAKE_STEPS = 8.0F;
    private static final float WAKE_GLOSS = 0.3F;
    /** Tanks narrower than this (node pixels) are too small for waves to be seen. */
    private static final float MIN_WAVE_SIZE = 3.0F;

    // Scratch space: rendering happens on one thread.
    private static final float[] RING_X = new float[SEGMENTS];
    private static final float[] RING_Z = new float[SEGMENTS];
    private static final float[] POLY_A = new float[MAX_POINTS * 3];
    private static final float[] POLY_B = new float[MAX_POINTS * 3];
    private static final float[] CUT = new float[(SEGMENTS * 3) * 3];
    private static final float[] ANGLES = new float[SEGMENTS * 3];
    private static final float[] EDGE = new float[WAVE_POINTS * 3];
    private static final float[] VERTS = new float[MAX_VERTICES * 3];
    private static final float[] NORMALS = new float[MAX_VERTICES * 3];
    private static final float[] COLORS = new float[MAX_VERTICES * 4];
    private static final Vector3f POINT = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();

    private LiquidRenderer() {
    }

    /**
     * @param up       the world's up in the node's space (unit), sloshing included
     * @param lightDir the direction to the scene's light in the node's space (unit)
     */
    public static void render(TankBinding binding, LiquidSim sim, Vector3fc up, Vector3fc lightDir, PoseStack poseStack,
            SceneBuffers buffers, int light) {
        LiquidLook look = sim.look();
        float fraction = sim.fraction();
        if (look == null || fraction < 0.002F) {
            return;
        }
        Box inner = binding.inner();
        float cx = (inner.from().x() + inner.to().x()) / 2.0F;
        float cy = (inner.from().y() + inner.to().y()) / 2.0F;
        float cz = (inner.from().z() + inner.to().z()) / 2.0F;
        float hx = (inner.to().x() - inner.from().x()) / 2.0F;
        float hy = (inner.to().y() - inner.from().y()) / 2.0F;
        float hz = (inner.to().z() - inner.from().z()) / 2.0F;
        float level = LiquidVolume.level(hx, hy, hz, up.x(), up.y(), up.z(), fraction);

        int count = binding.round() ? SEGMENTS : 4;
        for (int i = 0; i < count; i++) {
            if (binding.round()) {
                double angle = Math.PI * 2.0 * i / count;
                RING_X[i] = (float) Math.cos(angle) * hx;
                RING_Z[i] = (float) Math.sin(angle) * hz;
            } else {
                // Corners in the same turning sense as the circle's points.
                RING_X[i] = i == 0 || i == 3 ? hx : -hx;
                RING_Z[i] = i == 0 || i == 1 ? hz : -hz;
            }
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(look.sprite());
        Pass pass = new Pass(buffers.getBuffer(Sheets.translucentBlockItemSheet()), poseStack.last(), sprite,
                look.emissive() ? LightCoordsUtil.FULL_BRIGHT : light, sim.color(), cx, cy, cz, Math.max(hy, 0.5F), up, level,
                Math.max(SPRITE_SIZE, (hx + hz / 2.0F) * 2.0F), Math.max(SPRITE_SIZE, (hz + hy) * 2.0F));
        pass.eye = poseStack.last().pose().invert(new Matrix4f()).transformPosition(new Vector3f()).sub(cx, cy, cz);
        pass.sheen = 1.0F - 0.75F * look.viscosity();

        // Far walls first in thought (the buffer sorts them): the inside of the volume, seen through its near side.
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            set(POLY_A, 0, RING_X[i], -hy, RING_Z[i]);
            set(POLY_A, 1, RING_X[i], hy, RING_Z[i]);
            set(POLY_A, 2, RING_X[j], hy, RING_Z[j]);
            set(POLY_A, 3, RING_X[j], -hy, RING_Z[j]);
            int points = clip(POLY_A, 4, POLY_B, up, level);
            float nx = (RING_X[i] + RING_X[j]) / 2.0F;
            float nz = (RING_Z[i] + RING_Z[j]) / 2.0F;
            float length = Math.max(1.0e-4F, (float) Math.hypot(nx, nz));
            pass.wall(POLY_B, points, nx / length, 0.0F, nz / length);
        }
        for (int i = 0; i < count; i++) {
            set(POLY_A, i, RING_X[i], -hy, RING_Z[i]);
        }
        pass.wall(POLY_B, clip(POLY_A, count, POLY_B, up, level), 0.0F, -1.0F, 0.0F);
        for (int i = 0; i < count; i++) {
            set(POLY_A, i, RING_X[count - 1 - i], hy, RING_Z[count - 1 - i]);
        }
        pass.wall(POLY_B, clip(POLY_A, count, POLY_B, up, level), 0.0F, 1.0F, 0.0F);

        int cuts = surfacePoints(count, hy, up, level);
        if (cuts >= 3) {
            boolean waves = sim.isRippling() && Math.min(hx, hz) * 2.0F >= MIN_WAVE_SIZE;
            if ((sim.mottle() > 0.01F || sim.edgeBoil() > 0.01F || sim.hasWake()) && !binding.round() && up.y() > MOTTLE_UPRIGHT && pass.eye.y > 0.0F) {
                pass.mottled(hx, hz, sim);
            } else {
                pass.surface(cuts, sim, lightDir, waves);
            }
        }
    }

    private static void set(float[] points, int index, float x, float y, float z) {
        points[index * 3] = x;
        points[index * 3 + 1] = y;
        points[index * 3 + 2] = z;
    }

    /** Keeps the part of a convex polygon under the plane; returns how many points {@code to} holds. */
    private static int clip(float[] from, int count, float[] to, Vector3fc up, float level) {
        int out = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float x0 = from[i * 3];
            float y0 = from[i * 3 + 1];
            float z0 = from[i * 3 + 2];
            float x1 = from[j * 3];
            float y1 = from[j * 3 + 1];
            float z1 = from[j * 3 + 2];
            float d0 = up.x() * x0 + up.y() * y0 + up.z() * z0 - level;
            float d1 = up.x() * x1 + up.y() * y1 + up.z() * z1 - level;
            if (d0 <= 0.0F && out < MAX_POINTS) {
                set(to, out++, x0, y0, z0);
            }
            if ((d0 < 0.0F) != (d1 < 0.0F) && d0 != d1 && out < MAX_POINTS) {
                float t = d0 / (d0 - d1);
                set(to, out++, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t);
            }
        }
        return out;
    }

    /** Where the surface plane cuts the prism's edges, into {@link #CUT}: the corners of the surface, in no order. */
    private static int surfacePoints(int count, float hy, Vector3fc up, float level) {
        int found = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            found = cut(found, RING_X[i], -hy, RING_Z[i], RING_X[i], hy, RING_Z[i], up, level);
            found = cut(found, RING_X[i], -hy, RING_Z[i], RING_X[j], -hy, RING_Z[j], up, level);
            found = cut(found, RING_X[i], hy, RING_Z[i], RING_X[j], hy, RING_Z[j], up, level);
        }
        return found;
    }

    private static int cut(int found, float x0, float y0, float z0, float x1, float y1, float z1, Vector3fc up, float level) {
        float d0 = up.x() * x0 + up.y() * y0 + up.z() * z0 - level;
        float d1 = up.x() * x1 + up.y() * y1 + up.z() * z1 - level;
        if ((d0 < 0.0F) == (d1 < 0.0F) || d0 == d1) {
            return found;
        }
        float t = d0 / (d0 - d1);
        set(CUT, found, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t);
        return found + 1;
    }

    /** The look of a mixture: colours blended by amount; the main liquid's sprite and glow. */
    public static LiquidLook look(TankContents contents) {
        float a = 0.0F;
        float r = 0.0F;
        float g = 0.0F;
        float b = 0.0F;
        int total = Math.max(contents.amount(), 1);
        for (TankContents.Portion portion : contents.portions()) {
            int color = ClientRegistry.liquid(portion.liquid()).color();
            float weight = (float) portion.amount() / total;
            a += (color >>> 24) * weight;
            r += (color >> 16 & 0xFF) * weight;
            g += (color >> 8 & 0xFF) * weight;
            b += (color & 0xFF) * weight;
        }
        Identifier main = contents.main();
        LiquidLook mainLook = ClientRegistry.liquid(main);
        int blended = Math.round(a) << 24 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
        return new LiquidLook(blended, mainLook.sprite(), mainLook.emissive(), mainLook.viscosity());
    }

    /** One tank being drawn. Points are relative to the inner box's centre. */
    private static final class Pass {
        private final VertexConsumer consumer;
        private final PoseStack.Pose pose;
        private final float u0;
        private final float uSpan;
        private final float v0;
        private final float vSpan;
        private final int light;
        private final float red;
        private final float green;
        private final float blue;
        /** Log of what one unit of liquid lets through. */
        private final float clearness;
        private final float cx;
        private final float cy;
        private final float cz;
        private final float depthScale;
        private final Vector3fc up;
        private final float level;
        private final float spriteWidth;
        private final float spriteHeight;
        private Vector3f eye;
        /** How much of the light a wave catches: a thick liquid shows its waves softly. */
        private float sheen = 1.0F;

        Pass(VertexConsumer consumer, PoseStack.Pose pose, TextureAtlasSprite sprite, int light, int color, float cx, float cy, float cz,
                float depthScale, Vector3fc up, float level, float spriteWidth, float spriteHeight) {
            this.consumer = consumer;
            this.pose = pose;
            this.u0 = sprite.getU0();
            this.uSpan = sprite.getU1() - u0;
            this.v0 = sprite.getV0();
            this.vSpan = sprite.getV1() - v0;
            this.light = light;
            this.clearness = (float) Math.log(Math.max(1.0F - (color >>> 24) / 255.0F, 0.002F));
            this.red = (color >> 16 & 0xFF) / 255.0F;
            this.green = (color >> 8 & 0xFF) / 255.0F;
            this.blue = (color & 0xFF) / 255.0F;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.depthScale = depthScale;
            this.up = up;
            this.level = level;
            this.spriteWidth = spriteWidth;
            this.spriteHeight = spriteHeight;
        }

        /** A wall of the volume: its outside, and its inside for when the far side shows through. */
        void wall(float[] points, int count, float nx, float ny, float nz) {
            if (count < 3) {
                return;
            }
            for (int first = 1; first < count - 1; first += 2) {
                int second = Math.min(first + 1, count - 1);
                int third = Math.min(first + 2, count - 1);
                vertexOf(points, 0, nx, ny, nz, false);
                vertexOf(points, first, nx, ny, nz, false);
                vertexOf(points, second, nx, ny, nz, false);
                vertexOf(points, third, nx, ny, nz, false);
                vertexOf(points, third, -nx, -ny, -nz, true);
                vertexOf(points, second, -nx, -ny, -nz, true);
                vertexOf(points, first, -nx, -ny, -nz, true);
                vertexOf(points, 0, -nx, -ny, -nz, true);
            }
        }

        private void vertexOf(float[] points, int index, float nx, float ny, float nz, boolean inside) {
            float x = points[index * 3];
            float y = points[index * 3 + 1];
            float z = points[index * 3 + 2];
            // Light fades with depth: the liquid thickens and darkens towards the bottom, measured from the surface.
            float depth = Math.max(0.0F, level - (up.x() * x + up.y() * y + up.z() * z));
            float deep = 1.0F - (float) Math.exp(-depth / (depthScale * 1.1F));
            float shade = inside ? 0.8F - 0.3F * deep : 1.0F - 0.42F * deep;
            float a = inside ? thickness(FAR_PATH + FAR_PATH_DEEP * deep) : thickness(NEAR_PATH + NEAR_PATH_DEEP * deep);
            emit(x, y, z, nx, ny, nz, red * shade, green * shade, blue * shade, a);
        }

        /** Opacity of a layer of liquid: light falls off exponentially with the path through it. */
        private float thickness(float path) {
            return 1.0F - (float) Math.exp(clearness * path);
        }

        private void emit(float x, float y, float z, float nx, float ny, float nz, float r, float g, float b, float a) {
            pose.pose().transformPosition(x + cx, y + cy, z + cz, POINT);
            pose.normal().transform(nx, ny, nz, NORMAL);
            // The sprite cannot repeat within a polygon; a tank bigger than it shows it stretched rather than cut.
            float u = u0 + uSpan * Mth.clamp(0.5F + (x + z * 0.5F) / spriteWidth, SPRITE_INSET, 1.0F - SPRITE_INSET);
            float v = v0 + vSpan * Mth.clamp(0.5F + (z + y) / spriteHeight, SPRITE_INSET, 1.0F - SPRITE_INSET);
            consumer.addVertex(POINT.x, POINT.y, POINT.z, ARGB.colorFromFloat(Mth.clamp(a, 0.0F, 1.0F), Math.min(r, 1.0F),
                    Math.min(g, 1.0F), Math.min(b, 1.0F)), u, v, OverlayTexture.NO_OVERLAY, light, NORMAL.x, NORMAL.y, NORMAL.z);
        }

        /**
         * The surface of a liquid that is not one colour: a sheet of cells, each lighter or darker by where it lies in patches that turn
         * with the liquid. Cells next to the wall are the meniscus, and it is brightest in the middle, where the light falls in.
         */
        void mottled(float hx, float hz, LiquidSim sim) {
            int cells = MOTTLE_CELLS;
            float cos = Mth.cos(sim.turned());
            float sin = Mth.sin(sim.turned());
            float strength = sim.mottle() * MOTTLE_STRENGTH;
            float a = thickness(SURFACE_PATH);
            for (int row = 0; row < cells; row++) {
                float z0 = -hz + 2.0F * hz * row / cells;
                float z1 = -hz + 2.0F * hz * (row + 1) / cells;
                for (int column = 0; column < cells; column++) {
                    float x0 = -hx + 2.0F * hx * column / cells;
                    float x1 = -hx + 2.0F * hx * (column + 1) / cells;
                    float mx = (x0 + x1) / 2.0F;
                    float mz = (z0 + z1) / 2.0F;
                    // The sheet is read turned back, so that the patches go the way the liquid and what floats on it go.
                    float sx = mx * cos + mz * sin;
                    float sz = mz * cos - mx * sin;
                    float value = 0.65F * noise(sx * MOTTLE_SCALE, sz * MOTTLE_SCALE) + 0.35F * noise(sx * MOTTLE_DETAIL + 7.0F, sz * MOTTLE_DETAIL + 3.0F);
                    float stepped = Math.round(value * MOTTLE_STEPS) / MOTTLE_STEPS - 0.5F;
                    float middle = 1.0F - Math.max(Math.abs(mx) / hx, Math.abs(mz) / hz);
                    float body = 1.0F + stepped * strength * 2.0F + middle * sim.mottle() * 0.12F;
                    boolean rim = row == 0 || column == 0 || row == cells - 1 || column == cells - 1;
                    float r = red * body;
                    float g = green * body;
                    float b = blue * body;
                    if (rim) {
                        r += (1.0F - r) * 0.2F;
                        g += (1.0F - g) * 0.2F;
                        b += (1.0F - b) * 0.2F;
                    }
                    int fromWall = Math.min(Math.min(row, column), Math.min(cells - 1 - row, cells - 1 - column));
                    if (fromWall < SEETHE_CELLS && sim.edgeBoil() > 0.01F) {
                        // Every cell keeps its own beat, so the foam shivers instead of blinking all at once.
                        int spot = row * cells + column;
                        int beat = Mth.floor(sim.clock() * SEETHE_BEAT + hash(spot, 5) * 7.0F);
                        float chance = sim.edgeBoil() * (fromWall == 0 ? SEETHE_AT_WALL : SEETHE_INSIDE);
                        if (hash(spot, beat) < chance) {
                            r += (1.0F - r) * SEETHE_LIGHT;
                            g += (1.0F - g) * SEETHE_LIGHT;
                            b += (1.0F - b) * SEETHE_LIGHT;
                        }
                    }
                    float wake = Math.round(sim.wakeShade(column, row) * WAKE_STEPS) / WAKE_STEPS;
                    float gloss = Math.max(0.0F, wake) * WAKE_GLOSS;
                    r = r * (1.0F + wake) + (1.0F - r) * gloss;
                    g = g * (1.0F + wake) + (1.0F - g) * gloss;
                    b = b * (1.0F + wake) + (1.0F - b) * gloss;
                    float alpha = rim ? a + 0.15F : a;
                    cell(x0, z0, sim, hx, hz, r, g, b, alpha);
                    cell(x0, z1, sim, hx, hz, r, g, b, alpha);
                    cell(x1, z1, sim, hx, hz, r, g, b, alpha);
                    cell(x1, z0, sim, hx, hz, r, g, b, alpha);
                }
            }
        }

        private void cell(float x, float z, LiquidSim sim, float hx, float hz, float r, float g, float b, float a) {
            float flat = (level - up.x() * x - up.z() * z) / up.y();
            float wave = sim.height(0.5F + x / (2.0F * hx), 0.5F - z / (2.0F * hz));
            emit(x, flat - wave, z, up.x(), up.y(), up.z(), r, g, b, a);
        }

        /** Smooth noise, 0 to 1, the same every frame for the same point. */
        private static float noise(float x, float z) {
            int ix = Mth.floor(x);
            int iz = Mth.floor(z);
            float fx = x - ix;
            float fz = z - iz;
            fx = fx * fx * (3.0F - 2.0F * fx);
            fz = fz * fz * (3.0F - 2.0F * fz);
            float near = Mth.lerp(fx, hash(ix, iz), hash(ix + 1, iz));
            float far = Mth.lerp(fx, hash(ix, iz + 1), hash(ix + 1, iz + 1));
            return Mth.lerp(fz, near, far);
        }

        private static float hash(int x, int z) {
            int h = x * 374761393 + z * 668265263;
            h = (h ^ h >>> 13) * 1274126177;
            return ((h ^ h >>> 16) & 0xFFFF) / 65535.0F;
        }

        /** The surface: rings from its middle to the edge {@code cuts} corners in {@link #CUT} make, moved by the waves. */
        void surface(int cuts, LiquidSim sim, Vector3fc lightDir, boolean waves) {
            // A frame in the plane: t1 along x as far as the plane allows, t2 = up x t1, so that t1, t2, up turn like x, y, z.
            Vector3f t1 = new Vector3f(1.0F, 0.0F, 0.0F).fma(-up.x(), up);
            if (t1.lengthSquared() < 1.0e-4F) {
                t1.set(0.0F, 0.0F, 1.0F).fma(-up.z(), up);
            }
            t1.normalize();
            Vector3f t2 = new Vector3f(up).cross(t1);
            float mx = 0.0F;
            float my = 0.0F;
            float mz = 0.0F;
            for (int i = 0; i < cuts; i++) {
                mx += CUT[i * 3];
                my += CUT[i * 3 + 1];
                mz += CUT[i * 3 + 2];
            }
            mx /= cuts;
            my /= cuts;
            mz /= cuts;
            // Corners in order round the middle, turning the way that faces up.
            float extent1 = 1.0e-3F;
            float extent2 = 1.0e-3F;
            for (int i = 0; i < cuts; i++) {
                float dx = CUT[i * 3] - mx;
                float dy = CUT[i * 3 + 1] - my;
                float dz = CUT[i * 3 + 2] - mz;
                float a = dx * t1.x + dy * t1.y + dz * t1.z;
                float b = dx * t2.x + dy * t2.y + dz * t2.z;
                extent1 = Math.max(extent1, Math.abs(a));
                extent2 = Math.max(extent2, Math.abs(b));
                ANGLES[i] = (float) Math.atan2(b, a);
            }
            for (int i = 1; i < cuts; i++) {
                float angle = ANGLES[i];
                float x = CUT[i * 3];
                float y = CUT[i * 3 + 1];
                float z = CUT[i * 3 + 2];
                int j = i - 1;
                while (j >= 0 && ANGLES[j] > angle) {
                    ANGLES[j + 1] = ANGLES[j];
                    set(CUT, j + 1, CUT[j * 3], CUT[j * 3 + 1], CUT[j * 3 + 2]);
                    j--;
                }
                ANGLES[j + 1] = angle;
                set(CUT, j + 1, x, y, z);
            }
            int points = waves ? WAVE_POINTS : Math.min(cuts, WAVE_POINTS);
            for (int i = 0; i < points; i++) {
                // Round the edge at even steps of its corners.
                float at = (float) i / points * cuts;
                int a = (int) at % cuts;
                int b = (a + 1) % cuts;
                float t = at - (int) at;
                set(EDGE, i, CUT[a * 3] + (CUT[b * 3] - CUT[a * 3]) * t, CUT[a * 3 + 1] + (CUT[b * 3 + 1] - CUT[a * 3 + 1]) * t,
                        CUT[a * 3 + 2] + (CUT[b * 3 + 2] - CUT[a * 3 + 2]) * t);
            }
            float[] rings = waves ? WAVE_RINGS : CALM_RINGS;
            Vector3f view = new Vector3f();
            Vector3f half = new Vector3f();
            Vector3f normal = new Vector3f();
            float mean = up.x() * mx + up.y() * my + up.z() * mz;
            boolean above = up.x() * eye.x + up.y() * eye.y + up.z() * eye.z >= mean;
            for (int ring = 0; ring < rings.length; ring++) {
                float out = rings[ring];
                boolean rim = ring == rings.length - 1;
                for (int i = 0; i < points; i++) {
                    int at = ring * points + i;
                    float x = mx + (EDGE[i * 3] - mx) * out;
                    float y = my + (EDGE[i * 3 + 1] - my) * out;
                    float z = mz + (EDGE[i * 3 + 2] - mz) * out;
                    float s1 = (x - mx) * t1.x + (y - my) * t1.y + (z - mz) * t1.z;
                    float s2 = (x - mx) * t2.x + (y - my) * t2.y + (z - mz) * t2.z;
                    float u = 0.5F + s1 / (extent1 * 2.0F);
                    float v = 0.5F + s2 / (extent2 * 2.0F);
                    normal.set(up);
                    if (waves) {
                        // The edge stays on the glass; the waves live inside it.
                        float lift = rim ? 0.0F : -sim.height(u, v);
                        x += up.x() * lift;
                        y += up.y() * lift;
                        z += up.z() * lift;
                        float e = 0.09F;
                        float slopeU = (sim.height(u + e, v) - sim.height(u - e, v)) / (4.0F * e * extent1);
                        float slopeV = (sim.height(u, v + e) - sim.height(u, v - e)) / (4.0F * e * extent2);
                        normal.fma(slopeU * 2.2F, t1).fma(slopeV * 2.2F, t2).normalize();
                    }
                    set(VERTS, at, x, y, z);
                    set(NORMALS, at, normal.x, normal.y, normal.z);
                    view.set(eye).sub(x, y, z).normalize();
                    float facing = Math.abs(normal.dot(view));
                    float grazing = 1.0F - facing;
                    float fresnel = 0.04F + 0.96F * grazing * grazing * grazing * grazing;
                    float highlight = 0.0F;
                    if (above) {
                        half.set(lightDir).add(view).normalize();
                        float dot = Math.max(0.0F, normal.dot(half));
                        highlight = (float) Math.pow(dot, 48.0) * sheen;
                    }
                    float sky = above ? fresnel * 0.75F : 0.0F;
                    // A wave's slope turned to the light is brighter, the side turned away darker: the waves read as shape.
                    float slope = waves ? (normal.dot(lightDir) - up.x() * lightDir.x() - up.y() * lightDir.y() - up.z() * lightDir.z()) * 2.4F * sheen : 0.0F;
                    float body = (above ? 1.06F : 0.7F) + Mth.clamp(slope, -0.35F, 0.6F);
                    float r = red * body * (1.0F - sky) + SKY_R * sky + highlight;
                    float g = green * body * (1.0F - sky) + SKY_G * sky + highlight;
                    float b = blue * body * (1.0F - sky) + SKY_B * sky + highlight;
                    float a = thickness(SURFACE_PATH) + fresnel * 0.4F + highlight * 0.6F;
                    if (rim) {
                        // The meniscus: the bright line where the liquid climbs the glass.
                        r += (1.0F - r) * 0.45F;
                        g += (1.0F - g) * 0.45F;
                        b += (1.0F - b) * 0.45F;
                        a += 0.22F;
                    }
                    COLORS[at * 4] = r;
                    COLORS[at * 4 + 1] = g;
                    COLORS[at * 4 + 2] = b;
                    COLORS[at * 4 + 3] = a;
                }
            }
            for (int ring = 0; ring < rings.length - 1; ring++) {
                for (int i = 0; i < points; i++) {
                    int j = (i + 1) % points;
                    int innerA = ring * points + i;
                    int outerA = innerA + points;
                    int innerB = ring * points + j;
                    int outerB = innerB + points;
                    if (above) {
                        surfaceVertex(innerA, 1.0F);
                        surfaceVertex(outerA, 1.0F);
                        surfaceVertex(outerB, 1.0F);
                        surfaceVertex(innerB, 1.0F);
                    } else {
                        surfaceVertex(innerB, -1.0F);
                        surfaceVertex(outerB, -1.0F);
                        surfaceVertex(outerA, -1.0F);
                        surfaceVertex(innerA, -1.0F);
                    }
                }
            }
        }

        private void surfaceVertex(int index, float side) {
            emit(VERTS[index * 3], VERTS[index * 3 + 1], VERTS[index * 3 + 2], NORMALS[index * 3] * side, NORMALS[index * 3 + 1] * side,
                    NORMALS[index * 3 + 2] * side, COLORS[index * 4], COLORS[index * 4 + 1], COLORS[index * 4 + 2], COLORS[index * 4 + 3]);
        }
    }
}
