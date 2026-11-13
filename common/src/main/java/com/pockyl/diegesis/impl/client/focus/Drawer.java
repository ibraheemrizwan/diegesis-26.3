package com.pockyl.diegesis.impl.client.focus;

import com.pockyl.diegesis.api.scene.InventoryDrawer;
import com.pockyl.diegesis.impl.client.anim.Easing;

/**
 * The inventory drawer's state: whether it is out, how far, and whether the player pinned it. It opens after the cursor
 * rests at its side for a moment and closes a moment after the cursor left, so passing by or overshooting does nothing.
 */
final class Drawer {
    private float amount;
    private float resting;
    private float away;
    private boolean open;
    private boolean pinned;

    /**
     * @param inside  the cursor is on the side's strip, the icon or the drawer
     * @param blocked the hand is busy with something in the scene: a drawer that is shut stays shut
     */
    void update(InventoryDrawer config, float dt, boolean inside, boolean blocked) {
        if (pinned || config.alwaysOpen()) {
            open = true;
        } else if (inside && (open || !blocked)) {
            away = 0.0F;
            resting += dt;
            if (resting >= config.openDelay()) {
                open = true;
            }
        } else {
            resting = 0.0F;
            if (open) {
                away += dt;
                if (away >= config.closeDelay()) {
                    open = false;
                    away = 0.0F;
                }
            }
        }
        float target = open ? 1.0F : 0.0F;
        float step = config.slideTime() <= 0.0F ? 1.0F : dt / config.slideTime();
        amount += Math.signum(target - amount) * Math.min(Math.abs(target - amount), step);
    }

    /** How far out it is, 0 to 1, eased. */
    float shown() {
        return Easing.outCubic(amount);
    }

    boolean isOpen() {
        return open;
    }

    boolean isPinned() {
        return pinned;
    }

    void togglePin() {
        setPinned(!pinned);
    }

    /** Pins it open, or lets it go: it then closes by itself unless the cursor is on it. */
    void setPinned(boolean value) {
        pinned = value;
        if (value) {
            open = true;
        }
        away = 0.0F;
    }

    /** Shut at once, for a new station. */
    void reset() {
        amount = 0.0F;
        resting = 0.0F;
        away = 0.0F;
        open = false;
        pinned = false;
    }
}
