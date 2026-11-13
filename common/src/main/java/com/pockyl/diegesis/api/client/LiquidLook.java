package com.pockyl.diegesis.api.client;

import net.minecraft.resources.Identifier;

/**
 * How a liquid looks in the scene's tanks. Water and lava are known; mods describe their own liquids (reagents) with
 * {@link DiegesisClient#registerLiquid}. A mixture blends the colours of its parts by amount.
 *
 * @param color    ARGB colour; the alpha is how thick the liquid is: {@code 0xFF} hides what is behind it, {@code 0x60}
 *                 stays clear, and it thickens with the depth below the surface
 * @param sprite   a block-atlas sprite drawn on the liquid, tinted by the colour. {@link #LIQUID_SPRITE} suits most
 *                 liquids; a sprite with its own transparency thins the liquid further, as {@link #WATER_SPRITE} does
 * @param emissive glows in the dark (lava, glowing potions)
 * @param viscosity how thick it moves, 0 (water) to 1: waves run slower, die sooner and catch less light, the surface rocks
 *                 less. A soup, oil, honey
 */
public record LiquidLook(int color, Identifier sprite, boolean emissive, float viscosity) {
    /** Vanilla's flow, opaque and nearly white: the colour shows as it is and the alpha alone decides the thickness. */
    public static final Identifier LIQUID_SPRITE = Identifier.fromNamespaceAndPath("diegesis", "block/liquid");
    /** Vanilla water: grey and 30% see-through by itself, so whatever it tints comes out dim and thin. */
    public static final Identifier WATER_SPRITE = Identifier.withDefaultNamespace("block/water_still");
    public static final Identifier LAVA_SPRITE = Identifier.withDefaultNamespace("block/lava_still");

    public LiquidLook(int color, Identifier sprite, boolean emissive) {
        this(color, sprite, emissive, 0.0F);
    }

    /** A coloured liquid on {@link #LIQUID_SPRITE}. */
    public static LiquidLook of(int argb) {
        return new LiquidLook(argb, LIQUID_SPRITE, false);
    }

    public LiquidLook glowing() {
        return new LiquidLook(color, sprite, true, viscosity);
    }

    /** The same liquid moving as thickly as {@code amount}, 0 to 1. */
    public LiquidLook thick(float amount) {
        return new LiquidLook(color, sprite, emissive, Math.max(0.0F, Math.min(1.0F, amount)));
    }
}
