package com.pockyl.diegesis.impl.client.focus;

import net.minecraft.world.item.ItemStack;

import com.pockyl.diegesis.api.scene.CameraGlance;
import com.pockyl.diegesis.api.scene.InventoryDrawer.Side;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the player's look is between the rig and its glances. The look moves on a critically damped spring, so it starts
 * and settles softly and turning round half way keeps its speed. From one glance to another it goes through the rig.
 */
final class Glance {
    /** The spring's smooth time as a part of the glance's time: it is then within 1% of the goal at that time. */
    private static final float SMOOTH_PART = 0.3F;
    private static final float SETTLED = 0.002F;

    /** The glance the look is at or between it and the rig; null at rest on the rig. */
    private CameraGlance shot;
    /** The glance to go to once the look is back on the rig. */
    private CameraGlance next;
    private boolean out;
    private float amount;
    private float speed;

    private float resting;
    private final Set<String> spent = new HashSet<>();
    private boolean fresh = true;
    private ItemStack held = ItemStack.EMPTY;

    /** Moves the look; once per frame, also while the camera flies away from the station. */
    void advance(float dt) {
        if (!out && next != null && amount <= 0.0F) {
            shot = next;
            next = null;
            out = true;
        }
        if (shot == null) {
            return;
        }
        float goal = out ? 1.0F : 0.0F;
        float smooth = shot.time() * SMOOTH_PART;
        if (smooth <= 0.0F) {
            amount = goal;
            speed = 0.0F;
        } else {
            // Exact enough for any frame time: the usual closed form of a critically damped spring.
            float omega = 2.0F / smooth;
            float x = omega * dt;
            float decay = 1.0F / (1.0F + x + 0.48F * x * x + 0.235F * x * x * x);
            float change = amount - goal;
            float temp = (speed + omega * change) * dt;
            speed = (speed - omega * temp) * decay;
            amount = goal + (change + temp) * decay;
        }
        if (amount <= 0.0F || amount >= 1.0F) {
            amount = amount <= 0.0F ? 0.0F : 1.0F;
            speed = 0.0F;
        } else if (Math.abs(goal - amount) < SETTLED && Math.abs(speed) < SETTLED * 10.0F) {
            amount = goal;
            speed = 0.0F;
        }
        if (!out && amount <= 0.0F && next == null) {
            shot = null;
        }
    }

    /**
     * Follows the cursor and the hand; once per frame while the player can interact.
     *
     * @param u     the cursor across the screen, 0 to 1
     * @param v     the cursor down the screen, 0 to 1
     * @param free  nothing keeps the camera still: no object in the hand, no control or spreading drag under way, no
     *              inventory on the screen under the cursor
     * @param stack what is on the cursor
     */
    void watch(List<CameraGlance> glances, float u, float v, float dt, boolean free, ItemStack stack) {
        boolean took = took(stack);
        if (fresh) {
            fresh = false;
            spendStrips(glances, u, v);
        }
        CameraGlance current = current();
        if (current != null) {
            if (took && current.backOnTake()) {
                back(glances, u, v);
            } else if (free && inBackStrip(current, u, v)) {
                rest(dt, current.delay(), () -> back(glances, u, v));
            } else {
                resting = 0.0F;
            }
            return;
        }
        spent.removeIf(id -> glances.stream().noneMatch(glance -> glance.id().equals(id) && inStrip(glance, u, v)));
        CameraGlance reached = null;
        for (CameraGlance glance : glances) {
            if (inStrip(glance, u, v) && !spent.contains(glance.id())) {
                reached = glance;
                break;
            }
        }
        if (reached != null && free) {
            CameraGlance glance = reached;
            rest(dt, glance.delay(), () -> lookAt(glance));
        } else {
            resting = 0.0F;
        }
    }

    private void rest(float dt, float delay, Runnable then) {
        resting += dt;
        if (resting >= delay) {
            resting = 0.0F;
            then.run();
        }
    }

    /** Whether a stack came onto the cursor since the last frame: one more than before, or another one. */
    private boolean took(ItemStack stack) {
        boolean took = !stack.isEmpty() && (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, stack) || stack.getCount() > held.getCount());
        held = stack.copy();
        return took;
    }

    private void back(List<CameraGlance> glances, float u, float v) {
        lookBack();
        spendStrips(glances, u, v);
    }

    /** A cursor already in a strip does nothing there until it has left it. */
    private void spendStrips(List<CameraGlance> glances, float u, float v) {
        for (CameraGlance glance : glances) {
            if (inStrip(glance, u, v)) {
                spent.add(glance.id());
            }
        }
    }

    private static boolean inStrip(CameraGlance glance, float u, float v) {
        return glance.edge().isPresent() && atEdge(glance.edge().get(), glance.zone(), u, v);
    }

    private static boolean inBackStrip(CameraGlance glance, float u, float v) {
        return glance.edge().isPresent() && glance.backZone() > 0.0F && atEdge(opposite(glance.edge().get()), glance.backZone(), u, v);
    }

    private static boolean atEdge(Side edge, float zone, float u, float v) {
        return distance(edge, u, v) <= zone;
    }

    /** How far the cursor is from an edge, as a part of the screen. */
    static float distance(Side edge, float u, float v) {
        return switch (edge) {
            case LEFT -> u;
            case RIGHT -> 1.0F - u;
            case TOP -> v;
            case BOTTOM -> 1.0F - v;
        };
    }

    static Side opposite(Side edge) {
        return switch (edge) {
            case LEFT -> Side.RIGHT;
            case RIGHT -> Side.LEFT;
            case TOP -> Side.BOTTOM;
            case BOTTOM -> Side.TOP;
        };
    }

    void lookAt(CameraGlance glance) {
        resting = 0.0F;
        if (shot == null || shot.id().equals(glance.id())) {
            shot = glance;
            next = null;
            out = true;
        } else {
            next = glance;
            out = false;
        }
    }

    void lookBack() {
        resting = 0.0F;
        next = null;
        out = false;
    }

    /** The glance the look is at or on its way to; null when it stays on or goes back to the rig. */
    CameraGlance current() {
        return next != null ? next : out ? shot : null;
    }

    /** The glance the camera is blended with, or null. */
    CameraGlance shot() {
        return shot;
    }

    /** How far the look is from the rig to {@link #shot()}, 0 to 1. */
    float amount() {
        return shot == null ? 0.0F : amount;
    }

    /** On the rig at once, for a new station. */
    void reset() {
        shot = null;
        next = null;
        out = false;
        amount = 0.0F;
        speed = 0.0F;
        resting = 0.0F;
        spent.clear();
        fresh = true;
        held = ItemStack.EMPTY;
    }
}
