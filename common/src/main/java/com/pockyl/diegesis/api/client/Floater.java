package com.pockyl.diegesis.api.client;

import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.DiegesisMod;

/**
 * Something small floating on a liquid ({@link NodeView#setFloating}): a slice of carrot in a soup, a leaf on a pond. It rides the waves
 * and goes where the liquid turns.
 *
 * @param sprite a block-atlas sprite; the built-in ones are light greys made to be tinted, and any other works as well
 * @param color  an RGB colour multiplied into the sprite
 * @param size   how wide the sprite is drawn, in pixels
 * @param solid  it has a body: a slice of voxels, one for every pixel of the sprite, as an item's model is, and a strong current turns it over
 *               now and then; off for what is only a film on the surface, a drop of fat: the flat sprite
 */
public record Floater(Identifier sprite, int color, float size, boolean solid) {
    public Floater(Identifier sprite, int color, float size) {
        this(sprite, color, size, true);
    }

    /** A round slice. */
    public static final Identifier COIN = DiegesisMod.id("block/bits/coin");
    /** A rough lump: meat, a piece of potato. */
    public static final Identifier CHUNK = DiegesisMod.id("block/bits/chunk");
    /** A small lump. */
    public static final Identifier NUGGET = DiegesisMod.id("block/bits/nugget");
    /** A long thin strip. */
    public static final Identifier SHRED = DiegesisMod.id("block/bits/shred");
    /** A leaf, a flake of herbs. */
    public static final Identifier LEAF = DiegesisMod.id("block/bits/leaf");
    /** A drop of fat on a soup: tint it a little lighter than the liquid. */
    public static final Identifier DROP = DiegesisMod.id("block/bits/drop");
}
