package com.pockyl.diegesis.api.client;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * How a kind of scene particle looks and moves. Scene particles live in the station's own space, inside the scene: they
 * are small (sizes in pixels), drawn with the interface and never leave the block's area by much. Built-in styles are in
 * {@link DiegesisParticles}; mods add their own with {@link DiegesisClient#registerParticle}.
 *
 * @param sprites  sprites of the particle texture atlas ({@code minecraft:spark_0} ...), played as an animation over the
 *                 particle's life (one sprite: still)
 * @param color    ARGB tint
 * @param size     the smallest and largest size, in pixels
 * @param life     the shortest and longest life, in seconds
 * @param gravity  pixels per second squared pulling down (negative rises)
 * @param drag     fraction of the speed lost per second, 0-1
 * @param emissive drawn at full brightness (sparks, glints)
 * @param stretch  drawn stretched along its velocity (sparks, falling drops)
 * @param grow     how many times larger it gets by the end of its life (steam: 3, sparks: 0.3)
 * @param fade     fades out over its life instead of popping out at the end
 * @param wobble   pixels of sideways wandering (rising bubbles)
 * @param fadeIn   the part of its life (0 to 1) it takes to appear: steam rises out of nothing
 * @param surface  a particle style that takes over where it reaches a liquid's surface, in the same colour, and sends a small
 *                 ring over the liquid when it ends: a bubble swells there and pops. Null: it just ends at the surface
 */
public record ParticleStyle(List<Identifier> sprites, int color, float[] size, float[] life, float gravity, float drag,
        boolean emissive, boolean stretch, float grow, boolean fade, float wobble, float fadeIn, Identifier surface) {
    public ParticleStyle {
        if (sprites.isEmpty()) {
            throw new IllegalArgumentException("A particle style needs at least one sprite");
        }
        sprites = List.copyOf(sprites);
        size = size.clone();
        life = life.clone();
    }

    public ParticleStyle(List<Identifier> sprites, int color, float[] size, float[] life, float gravity, float drag, boolean emissive,
            boolean stretch, float grow, boolean fade, float wobble) {
        this(sprites, color, size, life, gravity, drag, emissive, stretch, grow, fade, wobble, 0.0F, null);
    }

    /** A style playing {@code sprites}, for a list made with {@link #frames}. */
    public static Builder builder(List<Identifier> sprites) {
        return new Builder(sprites);
    }

    public static Builder builder(Identifier... sprites) {
        return new Builder(List.of(sprites));
    }

    /** Sprites {@code minecraft:<base>_0} .. {@code <base>_<count - 1>}. */
    public static List<Identifier> frames(String base, int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> Identifier.withDefaultNamespace(base + "_" + i)).toList();
    }

    public static final class Builder {
        private final List<Identifier> sprites;
        private int color = 0xFFFFFFFF;
        private float[] size = {0.4F, 0.6F};
        private float[] life = {0.5F, 1.0F};
        private float gravity;
        private float drag = 0.5F;
        private boolean emissive;
        private boolean stretch;
        private float grow = 1.0F;
        private boolean fade = true;
        private float wobble;
        private float fadeIn;
        private Identifier surface;

        private Builder(List<Identifier> sprites) {
            this.sprites = sprites;
        }

        public Builder color(int argb) {
            color = argb;
            return this;
        }

        public Builder size(float min, float max) {
            size = new float[] {min, max};
            return this;
        }

        public Builder life(float minSeconds, float maxSeconds) {
            life = new float[] {minSeconds, maxSeconds};
            return this;
        }

        public Builder gravity(float pixelsPerSecondSquared) {
            gravity = pixelsPerSecondSquared;
            return this;
        }

        public Builder drag(float value) {
            drag = value;
            return this;
        }

        public Builder emissive() {
            emissive = true;
            return this;
        }

        public Builder stretch() {
            stretch = true;
            return this;
        }

        public Builder grow(float factor) {
            grow = factor;
            return this;
        }

        public Builder pop() {
            fade = false;
            return this;
        }

        public Builder wobble(float pixels) {
            wobble = pixels;
            return this;
        }

        /** It appears over this part of its life, 0 to 1. */
        public Builder fadeIn(float part) {
            fadeIn = part;
            return this;
        }

        /** Where it reaches a liquid's surface, the style {@code id} takes over (a bubble swells and pops). */
        public Builder surface(Identifier id) {
            surface = id;
            return this;
        }

        public ParticleStyle build() {
            return new ParticleStyle(sprites, color, size, life, gravity, drag, emissive, stretch, grow, fade, wobble, fadeIn, surface);
        }
    }
}
