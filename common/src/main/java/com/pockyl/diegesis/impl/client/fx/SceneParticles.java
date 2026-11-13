package com.pockyl.diegesis.impl.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.client.ParticleStyle;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.impl.client.ClientRegistry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The particles of one shown station, in its own space (pixels): spawned by emitters, effects and code, moved every
 * frame, drawn with the scene as sprites facing the camera (or stretched along their flight).
 */
public final class SceneParticles {
    /** A station never keeps more than this; the oldest give way. */
    private static final int MAX = 400;
    /**
     * Drawn after the see-through geometry, sorted back to front and writing no depth: a puff in front must not cut square holes
     * into the puffs behind it, and what is under a liquid's surface fades by its depth instead of being buried.
     */
    public static final RenderType TYPE = RenderTypes.entityTranslucent(TextureAtlas.LOCATION_PARTICLES);
    /** The wave a bubble leaves when it pops, about its height in pixels. */
    private static final float POP_RIPPLE = 0.09F;
    /** Where bubbles along a wall lie: how far from it at least, and over how wide a band, in pixels. */
    private static final float EDGE_GAP = 0.3F;
    private static final float EDGE_BAND = 0.8F;
    /** How fast a liquid's own opacity hides what is under its surface, per pixel of depth. */
    private static final float MURK = 0.4F;

    private final List<Particle> particles = new ArrayList<>();
    private final RandomSource random = RandomSource.create();

    public boolean isEmpty() {
        return particles.isEmpty();
    }

    /**
     * Adds {@code count} particles around {@code at} (station pixels), each with {@code velocity} plus some randomness.
     *
     * @param color an ARGB tint replacing the style's, or 0 for the style's own
     */
    public void spawn(ParticleStyle style, Vector3fc at, Vector3fc velocity, float spread, int count, int color) {
        spawn(style, at, velocity, spread, count, color, 1.0F);
    }

    /** {@link #spawn} with the particles' size scaled by {@code scale}. */
    public void spawn(ParticleStyle style, Vector3fc at, Vector3fc velocity, float spread, int count, int color, float scale) {
        for (int i = 0; i < count; i++) {
            if (particles.size() >= MAX) {
                particles.remove(0);
            }
            Vector3f pos = new Vector3f(at).add(gauss() * spread, gauss() * spread, gauss() * spread);
            float jitter = Math.max(0.3F, velocity.length() * 0.25F);
            Vector3f vel = new Vector3f(velocity).add(gauss() * jitter, gauss() * jitter, gauss() * jitter);
            float life = Mth.lerp(random.nextFloat(), style.life()[0], style.life()[1]);
            float size = Mth.lerp(random.nextFloat(), style.size()[0], style.size()[1]) * scale;
            particles.add(new Particle(style, pos, vel, life, size, color != 0 ? color : style.color(), random.nextFloat() * 100.0F,
                    Float.POSITIVE_INFINITY));
        }
    }

    /**
     * Particles leaving a liquid's surface: each starts at a random point of it, in the middle part of {@code inner}. Steam.
     *
     * @param fill the surface's height in the node's pixels
     */
    public void spawnOnLiquid(ParticleStyle style, Matrix4fc node, Box inner, float fill, Vector3fc velocity, int count, int color, float scale) {
        for (int i = 0; i < count; i++) {
            float x = Mth.lerp(0.1F + 0.8F * random.nextFloat(), inner.from().x(), inner.to().x());
            float z = Mth.lerp(0.1F + 0.8F * random.nextFloat(), inner.from().z(), inner.to().z());
            spawn(style, node.transformPosition(new Vector3f(x, fill, z)), velocity, 0.0F, 1, color, scale);
        }
    }

    /**
     * Particles on a liquid's surface along the walls of {@code inner}, where a pot starts to boil: they stay there and ring the liquid when
     * they end.
     *
     * @param fill the surface's height in the node's pixels
     */
    public void spawnAtEdge(ParticleStyle style, Matrix4fc node, Box inner, float fill, int count, int color, float scale, LiquidSim sim) {
        float width = inner.to().x() - inner.from().x();
        float depth = inner.to().z() - inner.from().z();
        for (int i = 0; i < count; i++) {
            if (particles.size() >= MAX) {
                particles.remove(0);
            }
            // Along one of the four walls, close to it.
            float along = random.nextFloat();
            float off = EDGE_GAP + random.nextFloat() * EDGE_BAND;
            int wall = random.nextInt(4);
            float u = wall < 2 ? along : wall == 2 ? off / width : 1.0F - off / width;
            float v = wall >= 2 ? along : wall == 0 ? off / depth : 1.0F - off / depth;
            float x = inner.from().x() + width * u;
            float z = inner.from().z() + depth * v;
            Vector3f pos = node.transformPosition(new Vector3f(x, fill + 0.05F, z));
            float life = Mth.lerp(random.nextFloat(), style.life()[0], style.life()[1]);
            float size = Mth.lerp(random.nextFloat(), style.size()[0], style.size()[1]) * scale;
            Particle particle = new Particle(style, pos, new Vector3f(), life, size, color != 0 ? color : style.color(), random.nextFloat() * 100.0F,
                    Float.POSITIVE_INFINITY);
            particle.sim = sim;
            particle.surfaceU = u;
            particle.surfaceV = 1.0F - v;
            particles.add(particle);
        }
    }

    /**
     * Particles inside a liquid: each starts at a random point of {@code inner} (the node's pixels) below the surface
     * at {@code fill} and is gone when it rises through the surface - bubbles in a flask.
     *
     * @param node the node's matrix (station space)
     * @param sim  the liquid they are in: it dims them by their depth, and rings where they pop
     */
    public void spawnInLiquid(ParticleStyle style, Matrix4fc node, Box inner, float fill, Vector3fc velocity, int count, int color,
            float scale, LiquidSim sim) {
        float bottom = inner.from().y();
        if (fill <= bottom + 0.2F) {
            return;
        }
        for (int i = 0; i < count; i++) {
            if (particles.size() >= MAX) {
                particles.remove(0);
            }
            float x = Mth.lerp(0.15F + 0.7F * random.nextFloat(), inner.from().x(), inner.to().x());
            float z = Mth.lerp(0.15F + 0.7F * random.nextFloat(), inner.from().z(), inner.to().z());
            float y = Mth.lerp(random.nextFloat() * 0.6F, bottom + 0.2F, fill);
            Vector3f pos = node.transformPosition(new Vector3f(x, y, z));
            float ceiling = node.transformPosition(new Vector3f(x, fill, z)).y;
            float jitter = Math.max(0.3F, velocity.length() * 0.25F);
            Vector3f vel = new Vector3f(velocity).add(gauss() * jitter * 0.3F, gauss() * jitter, gauss() * jitter * 0.3F);
            float life = Mth.lerp(random.nextFloat(), style.life()[0], style.life()[1]) * 3.0F;
            float size = Mth.lerp(random.nextFloat(), style.size()[0], style.size()[1]) * scale;
            Particle particle = new Particle(style, pos, vel, life, size, color != 0 ? color : style.color(), random.nextFloat() * 100.0F, ceiling);
            particle.sim = sim;
            particle.surfaceU = (x - inner.from().x()) / Math.max(0.01F, inner.to().x() - inner.from().x());
            particle.surfaceV = 1.0F - (z - inner.from().z()) / Math.max(0.01F, inner.to().z() - inner.from().z());
            particles.add(particle);
        }
    }

    private float gauss() {
        return (float) random.nextGaussian() * 0.5F;
    }

    public void update(float dt) {
        List<Particle> surfaced = null;
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle particle = iterator.next();
            particle.age += dt;
            if (particle.pos.y >= particle.ceiling) {
                iterator.remove();
                ParticleStyle next = particle.style.surface() == null ? null : ClientRegistry.particle(particle.style.surface());
                if (next != null) {
                    surfaced = surfaced == null ? new ArrayList<>() : surfaced;
                    surfaced.add(onSurface(particle, next));
                }
                continue;
            }
            if (particle.age >= particle.life) {
                iterator.remove();
                if (particle.sim != null && !Float.isFinite(particle.ceiling)) {
                    particle.sim.impulse(particle.surfaceU, particle.surfaceV, POP_RIPPLE);
                }
                continue;
            }
            ParticleStyle style = particle.style;
            particle.vel.y -= style.gravity() * dt;
            particle.vel.mul((float) Math.pow(1.0F - Mth.clamp(style.drag(), 0.0F, 0.999F), dt));
            if (style.wobble() > 0.0F) {
                float t = particle.age * 6.0F + particle.seed;
                particle.vel.x += (float) Math.sin(t) * style.wobble() * dt * 6.0F;
                particle.vel.z += (float) Math.cos(t * 1.3F) * style.wobble() * dt * 6.0F;
            }
            particle.pos.fma(dt, particle.vel);
        }
        if (surfaced != null) {
            particles.addAll(surfaced);
        }
    }

    /** What a particle becomes at the surface: it stays there, in the same colour, and rings the liquid when it ends. */
    private Particle onSurface(Particle from, ParticleStyle style) {
        float life = Mth.lerp(random.nextFloat(), style.life()[0], style.life()[1]);
        float size = Mth.lerp(random.nextFloat(), style.size()[0], style.size()[1]);
        Vector3f pos = new Vector3f(from.pos.x, from.ceiling + 0.05F, from.pos.z);
        Particle particle = new Particle(style, pos, new Vector3f(), life, size, from.color, from.seed, Float.POSITIVE_INFINITY);
        particle.sim = from.sim;
        particle.surfaceU = from.surfaceU;
        particle.surfaceV = from.surfaceV;
        return particle;
    }

    /**
     * Draws the particles; the pose stack is in station space.
     *
     * @param right camera right in station space (unit)
     * @param up    camera up in station space (unit)
     * @param view  the direction the camera looks, in station space (unit)
     */
    public void render(PoseStack poseStack, SceneBuffers buffers, Vector3fc right, Vector3fc up, Vector3fc view, int light) {
        if (particles.isEmpty()) {
            return;
        }
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
        VertexConsumer consumer = buffers.getBuffer(TYPE);
        PoseStack.Pose pose = poseStack.last();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        for (Particle particle : particles) {
            ParticleStyle style = particle.style;
            float t = particle.age / particle.life;
            int frame = Math.min(style.sprites().size() - 1, (int) (t * style.sprites().size()));
            TextureAtlasSprite sprite = atlas.getSprite(style.sprites().get(frame));
            float size = particle.size * Mth.lerp(t, 1.0F, style.grow()) / 2.0F;
            float alpha = (particle.color >>> 24) / 255.0F * (style.fade() ? 1.0F - t * t : 1.0F);
            if (style.fadeIn() > 0.0F) {
                alpha *= Math.min(1.0F, t / style.fadeIn());
            }
            if (particle.sim != null && Float.isFinite(particle.ceiling)) {
                // Under the surface it shows through what lies above it.
                float opacity = (particle.sim.color() >>> 24) / 255.0F;
                float clearness = (float) Math.log(Math.max(1.0F - opacity, 0.02F));
                alpha *= (float) Math.exp(clearness * MURK * Math.max(0.0F, particle.ceiling - particle.pos.y));
            }
            if (style.stretch() && particle.vel.lengthSquared() > 0.01F) {
                // Along the flight: the long side follows the velocity, the short side faces the camera.
                Vector3f along = new Vector3f(particle.vel).normalize();
                a.set(along).mul(size * (1.0F + particle.vel.length() * 0.06F));
                b.set(along).cross(view).normalize().mul(size * 0.5F);
            } else {
                a.set(up).mul(size);
                b.set(right).mul(size);
            }
            int packed = style.emissive() ? LightCoordsUtil.FULL_BRIGHT : light;
            int color = particle.color;
            Vector3f p = particle.pos;
            corner(consumer, pose, p.x - b.x - a.x, p.y - b.y - a.y, p.z - b.z - a.z, sprite.getU1(), sprite.getV1(), color, alpha, packed);
            corner(consumer, pose, p.x - b.x + a.x, p.y - b.y + a.y, p.z - b.z + a.z, sprite.getU1(), sprite.getV0(), color, alpha, packed);
            corner(consumer, pose, p.x + b.x + a.x, p.y + b.y + a.y, p.z + b.z + a.z, sprite.getU0(), sprite.getV0(), color, alpha, packed);
            corner(consumer, pose, p.x + b.x - a.x, p.y + b.y - a.y, p.z + b.z - a.z, sprite.getU0(), sprite.getV1(), color, alpha, packed);
        }
    }

    private static void corner(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color,
            float alpha, int light) {
        Vector3f point = pose.pose().transformPosition(new Vector3f(x, y, z));
        consumer.addVertex(point.x, point.y, point.z, ARGB.color(Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F), color), u, v,
                OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
    }

    private static final class Particle {
        private final ParticleStyle style;
        private final Vector3f pos;
        private final Vector3f vel;
        private final float life;
        private final float size;
        private final int color;
        private final float seed;
        /** The height (station space) the particle ends at: a liquid's surface for bubbles. */
        private final float ceiling;
        private float age;
        /** The liquid it is in or on, and where on its surface (0 to 1 across the tank). */
        private LiquidSim sim;
        private float surfaceU;
        private float surfaceV;

        Particle(ParticleStyle style, Vector3f pos, Vector3f vel, float life, float size, int color, float seed, float ceiling) {
            this.style = style;
            this.pos = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
            this.color = color;
            this.seed = seed;
            this.ceiling = ceiling;
        }
    }
}
