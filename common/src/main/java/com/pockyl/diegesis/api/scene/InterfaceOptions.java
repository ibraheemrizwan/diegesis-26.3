package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * How the interface behaves and looks while a player works at the station: what of the normal screen is hidden, what
 * is drawn in the scene, how the held stack moves. Written as {@code "interface"} in a scene file; every field is
 * optional and defaults to the value below.
 *
 * @param hideHud        hide the HUD (hotbar, health, crosshair, effects)
 * @param hideChat       hide the chat
 * @param hidePlayer     do not draw the local player's own body (other players still see it)
 * @param inventoryPanel without an {@code inventory} layout in the scene, show the vanilla inventory panel on the screen
 *                       (stations with item slots); false shows no inventory at all
 * @param showCounts     write stack counts next to the items in the scene
 * @param countsOnTop    draw the counts over everything (never hidden by an item's model); false lets models cover them
 * @param highlightColor ARGB colour laid over the slot under the cursor
 * @param shadows        carried things cast a shadow of their shape onto the work surface
 * @param shadowOpacity  how dark those shadows are at the surface, 0 to 1
 * @param carryLift      how high (pixels) the held stack floats over the work surface
 * @param carryStick     the held stack settles onto the slot under the cursor (off: it stays exactly at the cursor)
 * @param carrySway      how much the held stack leans as it moves; 0 keeps it still
 * @param carryMargin    how far (pixels) beyond the block's sides the held stack and carried objects may go; empty for
 *                       no limit (they follow the cursor anywhere over the work plane)
 * @param sounds         the library's quiet sounds for picking up, putting down and operating controls
 * @param hoverOutline   the object under the cursor that the player can use (carry, operate, click, take) gets an
 *                       outline round its silhouette
 * @param outlineColor   RGB colour of that outline
 */
public record InterfaceOptions(boolean hideHud, boolean hideChat, boolean hidePlayer, boolean inventoryPanel, boolean showCounts,
        boolean countsOnTop, int highlightColor, boolean shadows, float shadowOpacity, float carryLift, boolean carryStick, float carrySway,
        Optional<Float> carryMargin, boolean sounds, boolean hoverOutline, int outlineColor) {
    public static final InterfaceOptions DEFAULT = new InterfaceOptions(true, true, false, true, true, true, 0x4DFFFFFF, true, 0.5F, 2.5F,
            false, 1.0F, Optional.empty(), true, true, 0xFFE6E6E6);

    public static final Codec<InterfaceOptions> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.BOOL, "hide_hud", DEFAULT.hideHud).forGetter(InterfaceOptions::hideHud),
            StrictCodecs.optional(Codec.BOOL, "hide_chat", DEFAULT.hideChat).forGetter(InterfaceOptions::hideChat),
            StrictCodecs.optional(Codec.BOOL, "hide_player", DEFAULT.hidePlayer).forGetter(InterfaceOptions::hidePlayer),
            StrictCodecs.optional(Codec.BOOL, "inventory_panel", DEFAULT.inventoryPanel).forGetter(InterfaceOptions::inventoryPanel),
            StrictCodecs.optional(Codec.BOOL, "show_counts", DEFAULT.showCounts).forGetter(InterfaceOptions::showCounts),
            StrictCodecs.optional(Codec.BOOL, "counts_on_top", DEFAULT.countsOnTop).forGetter(InterfaceOptions::countsOnTop),
            StrictCodecs.optional(Visual.COLOR, "highlight_color", DEFAULT.highlightColor).forGetter(InterfaceOptions::highlightColor),
            StrictCodecs.optional(Codec.BOOL, "shadows", DEFAULT.shadows).forGetter(InterfaceOptions::shadows),
            StrictCodecs.optional(Codec.floatRange(0.0F, 1.0F), "shadow_opacity", DEFAULT.shadowOpacity)
                    .forGetter(InterfaceOptions::shadowOpacity),
            StrictCodecs.optional(Codec.floatRange(0.0F, 32.0F), "carry_lift", DEFAULT.carryLift).forGetter(InterfaceOptions::carryLift),
            StrictCodecs.optional(Codec.BOOL, "carry_stick", DEFAULT.carryStick).forGetter(InterfaceOptions::carryStick),
            StrictCodecs.optional(Codec.floatRange(0.0F, 4.0F), "carry_sway", DEFAULT.carrySway).forGetter(InterfaceOptions::carrySway),
            StrictCodecs.optional(Codec.floatRange(0.0F, 256.0F), "carry_margin").forGetter(InterfaceOptions::carryMargin),
            StrictCodecs.optional(Codec.BOOL, "sounds", DEFAULT.sounds).forGetter(InterfaceOptions::sounds),
            StrictCodecs.optional(Codec.BOOL, "hover_outline", DEFAULT.hoverOutline).forGetter(InterfaceOptions::hoverOutline),
            StrictCodecs.optional(Visual.COLOR, "outline_color", DEFAULT.outlineColor).forGetter(InterfaceOptions::outlineColor)
    ).apply(instance, InterfaceOptions::new));

    /** Starts from the defaults, for scenes built in code. */
    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private boolean hideHud;
        private boolean hideChat;
        private boolean hidePlayer;
        private boolean inventoryPanel;
        private boolean showCounts;
        private boolean countsOnTop;
        private int highlightColor;
        private boolean shadows;
        private float shadowOpacity;
        private float carryLift;
        private boolean carryStick;
        private float carrySway;
        private Optional<Float> carryMargin;
        private boolean sounds;
        private boolean hoverOutline;
        private int outlineColor;

        private Builder(InterfaceOptions from) {
            hideHud = from.hideHud;
            hideChat = from.hideChat;
            hidePlayer = from.hidePlayer;
            inventoryPanel = from.inventoryPanel;
            showCounts = from.showCounts;
            countsOnTop = from.countsOnTop;
            highlightColor = from.highlightColor;
            shadows = from.shadows;
            shadowOpacity = from.shadowOpacity;
            carryLift = from.carryLift;
            carryStick = from.carryStick;
            carrySway = from.carrySway;
            carryMargin = from.carryMargin;
            hoverOutline = from.hoverOutline;
            outlineColor = from.outlineColor;
            sounds = from.sounds;
        }

        public Builder hideHud(boolean value) {
            hideHud = value;
            return this;
        }

        public Builder hideChat(boolean value) {
            hideChat = value;
            return this;
        }

        public Builder hidePlayer(boolean value) {
            hidePlayer = value;
            return this;
        }

        public Builder inventoryPanel(boolean value) {
            inventoryPanel = value;
            return this;
        }

        public Builder showCounts(boolean value) {
            showCounts = value;
            return this;
        }

        public Builder countsOnTop(boolean value) {
            countsOnTop = value;
            return this;
        }

        public Builder highlightColor(int argb) {
            highlightColor = argb;
            return this;
        }

        public Builder shadows(boolean value, float opacity) {
            shadows = value;
            shadowOpacity = opacity;
            return this;
        }

        public Builder carry(float lift, boolean stick, float sway) {
            carryLift = lift;
            carryStick = stick;
            carrySway = sway;
            return this;
        }

        /** Keeps carried things within {@code pixels} of the block's sides. */
        public Builder carryMargin(float pixels) {
            carryMargin = Optional.of(pixels);
            return this;
        }

        public Builder sounds(boolean value) {
            sounds = value;
            return this;
        }

        /** The outline round the usable object under the cursor; {@code rgb} its colour. */
        public Builder hoverOutline(boolean value, int rgb) {
            hoverOutline = value;
            outlineColor = rgb;
            return this;
        }

        public InterfaceOptions build() {
            return new InterfaceOptions(hideHud, hideChat, hidePlayer, inventoryPanel, showCounts, countsOnTop, highlightColor, shadows, shadowOpacity,
                    carryLift, carryStick, carrySway, carryMargin, sounds, hoverOutline, outlineColor);
        }
    }
}
