package com.pockyl.diegesis.api.client;

import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.DiegesisMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in scene particle styles, ready for scene emitters ({@code "particle": "diegesis:steam"}), client code
 * ({@code StationView.spawnParticles}) and server logic: each is also an effect, so
 * {@code station.effect(DiegesisParticles.SPARK, "burner")} throws a burst of sparks at a node.
 */
public final class DiegesisParticles {
    public static final Identifier SPARK = DiegesisMod.id("spark");
    public static final Identifier BUBBLE = DiegesisMod.id("bubble");
    /** A bubble on a liquid's surface: it swells for a moment and pops. {@link #BUBBLE} turns into it by itself. */
    public static final Identifier BUBBLE_POP = DiegesisMod.id("bubble_pop");
    public static final Identifier DROPLET = DiegesisMod.id("droplet");
    public static final Identifier SPLASH = DiegesisMod.id("splash");
    public static final Identifier STEAM = DiegesisMod.id("steam");
    public static final Identifier SMOKE = DiegesisMod.id("smoke");
    public static final Identifier GLINT = DiegesisMod.id("glint");
    public static final Identifier DUST = DiegesisMod.id("dust");
    public static final Identifier FLAME = DiegesisMod.id("flame");

    private DiegesisParticles() {
    }

    /** The library's own steam: vanilla's smoke frames in white, smoke's grey would read as something burning. */
    private static List<Identifier> steamFrames() {
        return java.util.stream.IntStream.range(0, 12).mapToObj(i -> DiegesisMod.id("steam_" + i)).toList();
    }

    /** The styles of the built-in particles. */
    public static Map<Identifier, ParticleStyle> builtIn() {
        Map<Identifier, ParticleStyle> styles = new LinkedHashMap<>();
        styles.put(SPARK, new ParticleStyle(ParticleStyle.frames("spark", 8), 0xFFFFC870, new float[] {0.5F, 0.8F}, new float[] {0.25F, 0.6F}, 60.0F, 0.6F, true,
                true, 0.3F, true, 0.0F));
        // The library's bubbles are vanilla's in white: vanilla's own are blue and turn muddy on anything but water.
        Identifier bubble = DiegesisMod.id("bubble");
        styles.put(BUBBLE, ParticleStyle.builder(bubble).color(0xDDFFFFFF).size(0.55F, 0.95F).life(0.6F, 1.4F).gravity(-6.0F).drag(0.8F).wobble(0.4F)
                .pop().surface(BUBBLE_POP).build());
        List<Identifier> popping = new ArrayList<>(List.of(bubble, bubble, bubble, bubble, bubble));
        for (int i = 0; i < 5; i++) {
            popping.add(DiegesisMod.id("bubble_pop_" + i));
        }
        styles.put(BUBBLE_POP, ParticleStyle.builder(popping).color(0xDDFFFFFF).size(0.8F, 1.3F).life(0.45F, 0.85F).drag(1.0F).grow(1.3F).pop().build());
        styles.put(DROPLET, ParticleStyle.builder(Identifier.withDefaultNamespace("drip_fall")).color(0xFF3F76E4).size(0.3F, 0.45F)
                .life(0.5F, 0.9F).gravity(60.0F).drag(0.1F).stretch().pop().build());
        styles.put(SPLASH, new ParticleStyle(ParticleStyle.frames("splash", 4), 0xFF3F76E4, new float[] {0.4F, 0.7F},
                new float[] {0.25F, 0.45F}, 40.0F, 0.3F, false, false, 1.0F, false, 0.0F));
        styles.put(STEAM, ParticleStyle.builder(steamFrames()).color(0x50FFFFFF).size(0.6F, 1.0F).life(1.0F, 1.7F).gravity(-3.0F).drag(0.7F).grow(2.0F)
                .wobble(0.5F).fadeIn(0.45F).emissive().build());
        styles.put(SMOKE, new ParticleStyle(ParticleStyle.frames("big_smoke", 12), 0xCC303030, new float[] {0.8F, 1.3F},
                new float[] {1.5F, 2.5F}, -3.0F, 0.9F, false, false, 2.5F, true, 0.5F));
        // The game's own "glint" sprite is green (bone meal's sparkle): a firework spark that twinkles out takes the colour asked for.
        styles.put(GLINT, ParticleStyle.builder(java.util.List.of(Identifier.withDefaultNamespace("spark_4"),
                Identifier.withDefaultNamespace("spark_3"), Identifier.withDefaultNamespace("spark_2"),
                Identifier.withDefaultNamespace("spark_1"), Identifier.withDefaultNamespace("spark_0"))).color(0xFFFFFFFF).size(0.4F, 0.7F)
                .life(0.3F, 0.7F).drag(1.0F).emissive().grow(0.2F).build());
        styles.put(DUST, new ParticleStyle(ParticleStyle.frames("generic", 8), 0x99C0B090, new float[] {0.15F, 0.3F},
                new float[] {1.0F, 2.0F}, 1.0F, 0.95F, false, false, 1.0F, true, 0.3F));
        styles.put(FLAME, ParticleStyle.builder(Identifier.withDefaultNamespace("flame")).color(0xFFFFFFFF).size(0.35F, 0.6F)
                .life(0.25F, 0.5F).gravity(-12.0F).drag(0.85F).emissive().grow(0.3F).wobble(0.15F).build());
        return styles;
    }
}
