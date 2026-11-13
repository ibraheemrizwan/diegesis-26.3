package com.pockyl.diegesis.api.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Something flat laid over the scene for a moment, drawn by the mod: a book of recipes, a map, a letter. It takes the mouse while it
 * is open; the scene goes on moving behind it. Open it with {@link StationView#showOverlay}. Escape closes it, and it is gone when the
 * player leaves the station.
 */
public interface ScreenOverlay {
    /** Draws it, in GUI pixels, over the scene and the inventory. */
    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);

    /** A mouse button went down; false closes the overlay, a click beside it usually. */
    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    default void mouseScrolled(double mouseX, double mouseY, double amount) {
    }

    /** It was closed, by the player or by {@code showOverlay}. */
    default void onClosed() {
    }
}
