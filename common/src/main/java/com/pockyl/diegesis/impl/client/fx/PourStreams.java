package com.pockyl.diegesis.impl.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.client.DiegesisParticles;
import com.pockyl.diegesis.api.client.LiquidLook;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.PourOptions;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.anim.Easing;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The streams of liquid running at a station, the local player's and other players'. A stream falls from the spout along
 * a parabola to the surface of the liquid it lands in: it starts with its head falling, thins as it speeds up, wavers,
 * stops by letting go of the spout and falling the rest of the way. Where it lands it pushes the surface, raising
 * ripples, and throws a few drops.
 */
public final class PourStreams {
    /** Pixels per second squared. */
    private static final float GRAVITY = 170.0F;
    private static final int SEGMENTS = 12;
    /** Seconds without news after which a stream counts as stopped. */
    private static final float STALE = 0.2F;
    /** The stream's strips across: where each edge lies (-1 to 1), and how solid it is there. */
    private static final float[] ACROSS = {-1.0F, -0.45F, 0.45F, 1.0F};
    private static final float[] SOLID = {0.12F, 0.95F, 0.95F, 0.12F};

    private final List<Stream> streams = new ArrayList<>();

    public boolean isEmpty() {
        return streams.isEmpty();
    }

    /**
     * Keeps a stream running this frame.
     *
     * @param source the node pouring
     * @param target the node it lands in
     * @param flow   how much runs, 0 to 1
     */
    public void feed(NodeInstance source, NodeInstance target, float flow) {
        for (Stream stream : streams) {
            if (stream.source == source && stream.target == target && !stream.cut) {
                stream.flowWanted = flow;
                stream.silence = 0.0F;
                return;
            }
        }
        if (flow > 0.0F) {
            streams.add(new Stream(source, target, flow));
        }
    }

    public void update(float dt, ClientStation station, PourOptions options) {
        Iterator<Stream> iterator = streams.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().update(dt, station, options)) {
                iterator.remove();
            }
        }
    }

    /**
     * Draws the streams; the pose stack is in station space.
     *
     * @param view the direction the camera looks, in station space (unit)
     */
    public void render(PoseStack poseStack, SceneBuffers buffers, Vector3fc view, int light, float width) {
        for (Stream stream : streams) {
            stream.render(poseStack, buffers, view, light, width);
        }
    }

    /** Where a node's spout is, in station space. */
    public static Vector3f spout(NodeInstance source) {
        return source.matrix().transformPosition(new Vector3f(source.definition().tank().get().spout().get()));
    }

    /**
     * Where something falling from {@code from} lands in a tank: on its liquid's surface (the bottom when it is empty),
     * kept inside the tank's walls. Station space.
     */
    public static Vector3f landing(NodeInstance target, Vector3fc from) {
        TankBinding binding = target.definition().tank().get();
        Box inner = binding.inner();
        Matrix4f toNode = target.matrix().invert(new Matrix4f());
        Vector3f local = toNode.transformPosition(new Vector3f(from));
        float marginX = Math.min(0.6F, (inner.to().x() - inner.from().x()) * 0.25F);
        float marginZ = Math.min(0.6F, (inner.to().z() - inner.from().z()) * 0.25F);
        local.x = Math.max(inner.from().x() + marginX, Math.min(inner.to().x() - marginX, local.x));
        local.z = Math.max(inner.from().z() + marginZ, Math.min(inner.to().z() - marginZ, local.z));
        local.y = inner.from().y() + (inner.to().y() - inner.from().y()) * target.liquid().fraction();
        return target.matrix().transformPosition(local);
    }

    /** One stream. Its head and tail are places along the fall, 0 at the spout, 1 where it lands. */
    private static final class Stream {
        private final NodeInstance source;
        private final NodeInstance target;
        private final Vector3f from = new Vector3f();
        private final Vector3f to = new Vector3f();
        private float flow;
        private float flowWanted;
        private float head;
        private float tail;
        private float silence;
        private float dropClock;
        private boolean cut;
        private int color;
        private LiquidLook look;

        Stream(NodeInstance source, NodeInstance target, float flow) {
            this.source = source;
            this.target = target;
            this.flowWanted = flow;
            this.look = source.liquid().look();
            this.color = source.liquid().color();
            from.set(spout(source));
            to.set(landing(target, from));
        }

        /** Returns true once it is gone. */
        boolean update(float dt, ClientStation station, PourOptions options) {
            silence += dt;
            if (silence > STALE) {
                flowWanted = 0.0F;
            }
            if (!cut) {
                from.set(spout(source));
                to.set(landing(target, from));
                color = source.liquid().color();
                if (source.liquid().look() != null) {
                    look = source.liquid().look();
                }
            }
            flow = Easing.approach(flow, flowWanted, 0.05F, dt);
            float fall = Math.max(0.05F, (float) Math.sqrt(2.0F * Math.max(0.5F, from.y - to.y) / GRAVITY));
            float speed = dt / fall;
            head = Math.min(1.0F, head + speed);
            if (flowWanted <= 0.0F) {
                cut = true;
            }
            if (cut) {
                tail = Math.min(1.0F, tail + speed);
            }
            if (head >= 1.0F && tail < 1.0F) {
                land(dt, station, options);
            }
            return cut && tail >= 1.0F;
        }

        /** The liquid's surface gives way under the stream, and it splashes a little. */
        private void land(float dt, ClientStation station, PourOptions options) {
            Box inner = target.definition().tank().get().inner();
            Vector3f local = target.matrix().invert(new Matrix4f()).transformPosition(new Vector3f(to));
            float u = (local.x - inner.from().x()) / Math.max(0.01F, inner.to().x() - inner.from().x());
            float v = 1.0F - (local.z - inner.from().z()) / Math.max(0.01F, inner.to().z() - inner.from().z());
            float strength = Math.max(flow, 0.15F);
            // The stream's foot wanders a little, so the rings do not come out too regular.
            float wander = 0.05F;
            target.liquid().impulse(u + ((float) Math.random() - 0.5F) * wander, v + ((float) Math.random() - 0.5F) * wander, strength * 7.0F * dt);
            dropClock -= dt;
            if (dropClock > 0.0F) {
                return;
            }
            dropClock = 0.05F + 0.1F * (1.0F - strength);
            // Drops catch the light: paler than the liquid in bulk.
            int drops = 0xFF000000 | (color >> 16 & 0xFF) / 2 + 0x70 << 16 | (color >> 8 & 0xFF) / 2 + 0x70 << 8 | (color & 0xFF) / 2 + 0x70;
            if (!options.stream()) {
                Vector3f fallDown = new Vector3f(to).sub(from).mul(4.0F);
                station.spawnParticles(DiegesisParticles.DROPLET, from, fallDown, 0.1F, 2, drops);
            }
            if (Math.random() < 0.55) {
                station.spawnParticles(DiegesisParticles.SPLASH, to, new Vector3f(0.0F, 7.0F + 6.0F * strength, 0.0F), 0.45F, 1, drops);
            }
            if (Math.random() < 0.3 * strength && target.liquid().fraction() > 0.01F) {
                station.spawnInLiquid(DiegesisParticles.BUBBLE, target.id(), 1, 0, new Vector3f(0.0F, 4.0F, 0.0F));
            }
        }

        private void at(float s, float time, float strength, Vector3f dest) {
            dest.set(from.x + (to.x - from.x) * s, from.y + (to.y - from.y) * s * s, from.z + (to.z - from.z) * s);
            // Wavers more the farther it has fallen, and a thin stream more than a full one.
            float waver = s * (0.1F + 0.14F * (1.0F - strength));
            dest.x += (float) Math.sin(s * 9.0F - time * 17.0F) * waver;
            dest.z += (float) Math.cos(s * 7.0F - time * 13.0F) * waver;
        }

        void render(PoseStack poseStack, SceneBuffers buffers, Vector3fc view, int light, float width) {
            if (look == null || head <= tail) {
                return;
            }
            TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(look.sprite());
            VertexConsumer consumer = buffers.getBuffer(Sheets.translucentBlockItemSheet());
            PoseStack.Pose pose = poseStack.last();
            int packed = look.emissive() ? LightCoordsUtil.FULL_BRIGHT : light;
            float time = (Util.getMillis() % 100000L) / 1000.0F;
            float strength = Math.max(0.1F, Math.min(1.0F, flow));
            float base = width * (float) Math.sqrt(strength);
            float red = (color >> 16 & 0xFF) / 255.0F;
            float green = (color >> 8 & 0xFF) / 255.0F;
            float blue = (color & 0xFF) / 255.0F;
            float alpha = Math.max(0.72F, (color >>> 24) / 255.0F);
            Vector3f toCamera = new Vector3f(view).negate();
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            Vector3f tangent = new Vector3f();
            Vector3f side = new Vector3f();
            Vector3f nextSide = new Vector3f();
            for (int i = 0; i < SEGMENTS; i++) {
                float s0 = tail + (head - tail) * i / SEGMENTS;
                float s1 = tail + (head - tail) * (i + 1) / SEGMENTS;
                at(s0, time, strength, a);
                at(s1, time, strength, b);
                tangent.set(b).sub(a);
                if (tangent.lengthSquared() < 1.0e-8F) {
                    continue;
                }
                tangent.cross(toCamera, side).normalize();
                nextSide.set(side);
                // It narrows as it speeds up: the same flow through a faster stream.
                float w0 = base * 0.5F / (float) Math.sqrt(1.0F + 3.0F * s0);
                float w1 = base * 0.5F / (float) Math.sqrt(1.0F + 3.0F * s1);
                float glow0 = 1.0F + 0.16F * (float) Math.sin(s0 * 13.0F - time * 21.0F);
                float glow1 = 1.0F + 0.16F * (float) Math.sin(s1 * 13.0F - time * 21.0F);
                for (int strip = 0; strip < ACROSS.length - 1; strip++) {
                    float e0 = ACROSS[strip];
                    float e1 = ACROSS[strip + 1];
                    float solid0 = SOLID[strip];
                    float solid1 = SOLID[strip + 1];
                    corner(consumer, pose, sprite, a, side, e0 * w0, e0, s0, time, red * glow0, green * glow0, blue * glow0, alpha * solid0, packed);
                    corner(consumer, pose, sprite, a, side, e1 * w0, e1, s0, time, red * glow0, green * glow0, blue * glow0, alpha * solid1, packed);
                    corner(consumer, pose, sprite, b, side, e1 * w1, e1, s1, time, red * glow1, green * glow1, blue * glow1, alpha * solid1, packed);
                    corner(consumer, pose, sprite, b, side, e0 * w1, e0, s1, time, red * glow1, green * glow1, blue * glow1, alpha * solid0, packed);
                }
            }
        }

        private static void corner(VertexConsumer consumer, PoseStack.Pose pose, TextureAtlasSprite sprite, Vector3f at, Vector3f side,
                float offset, float across, float along, float time, float r, float g, float b, float a, int light) {
            Vector3f point = pose.pose().transformPosition(at.x + side.x * offset, at.y + side.y * offset, at.z + side.z * offset, new Vector3f());
            Vector3f normal = pose.normal().transform(0.0F, 1.0F, 0.0F, new Vector3f());
            // Running down the sprite and back, so the texture never jumps at its edge.
            float running = along * 1.5F - time * 2.4F;
            // Short of the very edge: the atlas' next sprite would show through.
            float wave = 0.02F + Math.abs(running - (float) Math.floor(running) - 0.5F) * 1.92F;
            float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * (0.5F + across * 0.1F);
            float v = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * wave;
            int color = ARGB.colorFromFloat(Math.max(0.0F, Math.min(a, 1.0F)), Math.min(r, 1.0F), Math.min(g, 1.0F),
                    Math.min(b, 1.0F));
            consumer.addVertex(point.x, point.y, point.z, color, u, v, OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z);
        }
    }
}
