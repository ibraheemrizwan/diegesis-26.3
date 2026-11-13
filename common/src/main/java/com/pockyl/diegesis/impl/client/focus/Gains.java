package com.pockyl.diegesis.impl.client.focus;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2f;

import com.pockyl.diegesis.api.scene.GainOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What the player gets while working at a station, seen going into the inventory ({@link GainOptions}). A gain is what came into
 * the inventory and did not leave the cursor: a stack put down from the cursor or picked up onto it is the player's own move. The
 * server sends a slot and the cursor apart now and then, so a change waits a moment for its other half before it counts.
 */
final class Gains {
    /** Seconds a change waits for the rest of the same move. */
    private static final float SETTLE = 0.12F;
    /** How long a slot bounces after a stack landed in it, seconds, and how much bigger it gets. */
    private static final float BUMP_TIME = 0.25F;
    private static final float BUMP = 0.35F;
    /** How high over the straight way a stack flies at the middle, GUI pixels; how much bigger it starts. */
    private static final float ARC = 26.0F;
    private static final float START_SIZE = 1.4F;
    private static final float FADE = 0.3F;
    private static final int ASIDE_GAP = 6;
    /** "+N" over a slot once the stack is in (owner: "write +1, or more"): seconds it shows, the last of them fading, pixels it rises. */
    private static final float COUNT_TIME = 1.1F;
    private static final float COUNT_FADE = 0.4F;
    private static final float COUNT_RISE = 7.0F;
    private static final int COUNT_COLOR = 0xFFFFFF;
    private static final float Z = 260.0F;

    private final ItemStack[] last = new ItemStack[Inventory.INVENTORY_SIZE];
    private ItemStack lastCarried = ItemStack.EMPTY;
    private boolean fresh = true;
    private final List<Pending> pending = new ArrayList<>();
    private final List<Flight> flights = new ArrayList<>();
    private final Map<Integer, Float> bumps = new HashMap<>();
    private long lastFrame = Util.getNanos();

    /** A change of one kind of item not counted yet: what the inventory and the cursor together got, and what the inventory got. */
    private static final class Pending {
        private final ItemStack item;
        private int net;
        private int toInventory;
        private int slot = -1;
        private float age;

        private Pending(ItemStack item) {
            this.item = item.copyWithCount(1);
        }
    }

    /** A stack on its way from the station to its slot, or to stand next to the drawer ({@code slot} -1). */
    private static final class Flight {
        private final ItemStack stack;
        private final float fromX;
        private final float fromY;
        private final int slot;
        private final float time;
        private final float hold;
        private final boolean sound;
        private final String count;
        private float age;

        private Flight(ItemStack stack, Vector2f from, int slot, GainOptions options) {
            this.stack = stack;
            fromX = from.x;
            fromY = from.y;
            this.slot = slot;
            time = options.time();
            hold = slot < 0 ? options.hold() : 0.0F;
            sound = options.sound();
            count = options.count() ? "+" + stack.getCount() : null;
        }
    }

    /** For a new station: what the inventory holds now is the start. */
    void reset() {
        fresh = true;
        pending.clear();
    }

    /** Once per frame while the player can interact; {@code from} is where on the screen gains come from. */
    void watch(Inventory inventory, ItemStack carried, float dt, GainOptions options, Vector2f from) {
        if (fresh) {
            snapshot(inventory, carried);
            fresh = false;
            return;
        }
        for (int slot = 0; slot < last.length; slot++) {
            ItemStack before = last[slot];
            ItemStack now = inventory.getItem(slot);
            if (ItemStack.matches(before, now)) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(before, now)) {
                change(now, now.getCount() - before.getCount(), true, slot);
            } else {
                if (!before.isEmpty()) {
                    change(before, -before.getCount(), true, slot);
                }
                if (!now.isEmpty()) {
                    change(now, now.getCount(), true, slot);
                }
            }
            last[slot] = now.copy();
        }
        if (!ItemStack.matches(lastCarried, carried)) {
            if (ItemStack.isSameItemSameComponents(lastCarried, carried)) {
                change(carried, carried.getCount() - lastCarried.getCount(), false, -1);
            } else {
                if (!lastCarried.isEmpty()) {
                    change(lastCarried, -lastCarried.getCount(), false, -1);
                }
                if (!carried.isEmpty()) {
                    change(carried, carried.getCount(), false, -1);
                }
            }
            lastCarried = carried.copy();
        }
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending change = it.next();
            change.age += dt;
            if (change.age < SETTLE) {
                continue;
            }
            it.remove();
            int gained = Math.min(change.net, change.toInventory);
            if (gained > 0 && options.enabled()) {
                int slot = change.slot >= 0 && FocusScreen.isSlotShown(change.slot) ? change.slot : -1;
                flights.add(new Flight(change.item.copyWithCount(gained), from, slot, options));
            }
        }
    }

    private void change(ItemStack item, int amount, boolean inInventory, int slot) {
        if (amount == 0) {
            return;
        }
        Pending found = null;
        for (Pending candidate : pending) {
            if (ItemStack.isSameItemSameComponents(candidate.item, item)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            found = new Pending(item);
            pending.add(found);
        }
        found.net += amount;
        if (inInventory) {
            found.toInventory += amount;
            if (amount > 0) {
                found.slot = slot;
            }
        }
        found.age = 0.0F;
    }

    private void snapshot(Inventory inventory, ItemStack carried) {
        for (int slot = 0; slot < last.length; slot++) {
            last[slot] = inventory.getItem(slot).copy();
        }
        lastCarried = carried.copy();
    }

    /** How far the slot is through its bounce, 1 just landed, 0 at rest. */
    float bump(int slot) {
        Float left = bumps.get(slot);
        return left == null ? 0.0F : left / BUMP_TIME;
    }

    /** How much bigger the item in {@code slot} is drawn now. */
    float slotScale(int slot) {
        float bump = bump(slot);
        return 1.0F + BUMP * Mth.sin(bump * Mth.PI);
    }

    /**
     * Draws the stacks in the air and those standing next to the drawer; once per frame over the screen. {@code asideX, asideY}: where
     * the first one that has no slot on show stands.
     */
    void render(GuiGraphicsExtractor graphics, Font font, float asideX, float asideY) {
        long now = Util.getNanos();
        float dt = Math.min((now - lastFrame) / 1.0e9F, 0.1F);
        lastFrame = now;
        bumps.replaceAll((slot, left) -> left - dt);
        bumps.values().removeIf(left -> left <= 0.0F);
        int aside = 0;
        for (Iterator<Flight> it = flights.iterator(); it.hasNext(); ) {
            Flight flight = it.next();
            boolean landedBefore = flight.age >= flight.time;
            flight.age += dt;
            float toX;
            float toY;
            if (flight.slot >= 0) {
                int[] center = FocusScreen.slotCenter(flight.slot);
                toX = center[0];
                toY = center[1];
            } else {
                toX = asideX;
                toY = asideY - aside * (16 + ASIDE_GAP);
                aside++;
            }
            if (flight.age >= flight.time && !landedBefore) {
                land(flight);
            }
            float end = flight.time + Math.max(flight.hold, flight.slot >= 0 && flight.count != null ? COUNT_TIME : 0.0F);
            if (flight.age >= end) {
                it.remove();
                continue;
            }
            if (flight.slot >= 0 && flight.age >= flight.time) {
                // In its slot: the count rises from it and fades.
                float shown = (flight.age - flight.time) / COUNT_TIME;
                float alpha = Math.min(1.0F, (1.0F - shown) * COUNT_TIME / COUNT_FADE);
                float rise = COUNT_RISE * (1.0F - (1.0F - shown) * (1.0F - shown));
                if (flight.count != null) {
                    drawCount(graphics, font, flight.count, toX - font.width(flight.count) / 2.0F, toY - 17.0F - rise, alpha);
                }
                continue;
            }
            float t = Math.min(1.0F, flight.age / flight.time);
            float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            float x = Mth.lerp(eased, flight.fromX, toX);
            float y = Mth.lerp(eased, flight.fromY, toY) - ARC * Mth.sin(t * Mth.PI);
            float size = Mth.lerp(eased, START_SIZE, 1.0F);
            float alpha = 1.0F;
            if (flight.slot < 0 && flight.age > flight.time + flight.hold - FADE) {
                alpha = Math.max(0.0F, (flight.time + flight.hold - flight.age) / FADE);
            }
            draw(graphics, flight.stack, x, y, size, alpha);
            if (flight.slot < 0 && flight.age >= flight.time && flight.count != null) {
                // Beside the drawer it stands with what came, the count growing in as it settles.
                float shown = flight.age - flight.time;
                float countAlpha = Math.min(Math.min(1.0F, shown / 0.12F), alpha);
                drawCount(graphics, font, flight.count, x + 11.0F, y - 4.0F, countAlpha);
            }
        }
    }

    private void land(Flight flight) {
        if (flight.slot >= 0) {
            bumps.put(flight.slot, BUMP_TIME);
        }
        if (flight.sound) {
            float pitch = 1.5F + Minecraft.getInstance().level.getRandom().nextFloat() * 0.3F;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, pitch, 0.25F));
        }
    }

    private static void draw(GuiGraphicsExtractor graphics, ItemStack stack, float x, float y, float size, float alpha) {
        if (alpha <= 0.02F) {
            return;
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x,y);
        // GUI items cannot be drawn see-through: going away, it shrinks to nothing.
        float scale = size * alpha;
        pose.scale(scale,scale);
        graphics.item(stack, -8, -8);
        pose.popMatrix();
    }

    /** "+N" in the game's own font and shadow, as the counts in the slots. */
    private static void drawCount(GuiGraphicsExtractor graphics, Font font, String count, float x, float y, float alpha) {
        if (count == null) {
            return;
        }
        int a = Math.round(alpha * 255.0F);
        if (a < 5) {
            return;
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(Math.round(x),Math.round(y));
        graphics.text(font, count, 0, 0, a << 24 | COUNT_COLOR, true);
        pose.popMatrix();
    }
}
