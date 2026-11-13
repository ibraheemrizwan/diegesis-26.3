package com.pockyl.diegesis.api.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import com.pockyl.diegesis.api.event.SceneEvent;
import com.pockyl.diegesis.api.state.StationState;

/**
 * Client-side code of a station: animations, sounds and effects that react to the state and to what the player does.
 * Optional - a station works without one. Register it with {@link DiegesisClient#registerBehavior}; one object is
 * created per station shown on this client (in use by the local player, by someone nearby, or idle), so it may keep
 * per-station fields. All methods run on the render thread and have empty defaults.
 *
 * <p>For a client-only station this is the whole client logic; for a server station it is purely visual - real changes
 * go through the server ({@link StationView#sendAction}).
 */
public interface StationBehavior {
    /**
     * The scene's nodes are new: once when the station appears, and again when the scene is reloaded (F3+T). What code
     * did to the old nodes (moves, visuals, tooltips) is gone; put it back here.
     */
    default void onSceneReady(StationView view) {
    }

    /** The local player started working at the station. */
    default void onOpen(StationView view) {
    }

    /** The local player stopped working at the station. */
    default void onClose(StationView view) {
    }

    /** Every client tick while the station is shown. */
    default void tick(StationView view) {
    }

    /** Every frame before the station is drawn; {@code deltaSeconds} since the previous frame, for smooth animation. */
    default void frame(StationView view, float deltaSeconds) {
    }

    /** Slots, controls, tanks, outputs or the station's data changed (by any player, or by the server's logic). */
    default void onStateChanged(StationView view, StationState.Changes changes) {
    }

    /** The local player did something in the scene. */
    default void onEvent(StationView view, SceneEvent event) {
    }

    /**
     * The local player clicked a node with {@code stack} on the cursor, before the stack goes anywhere: dipping a bowl into a soup,
     * touching a tool to a part. Return true when that was handled here, usually with {@code view.sendAction}; the stack then stays
     * on the cursor and no slot sees the click. Any node with a hit box counts, and a stack let go there after a drag too.
     *
     * @param shift whether shift is held: for doing it to the whole stack at once
     */
    default boolean onItemClick(StationView view, String node, ItemStack stack, int button, boolean shift) {
        return false;
    }

    /** The station logic played an effect ({@code StationContext.effect}); runs after the effect itself. */
    /**
     * Shift + click on a stack of the player's inventory while working at the station. Return true when the station takes care
     * of it (it sends its own action); false lets the library move the stack into the station's slots as usual.
     *
     * @param slot  the inventory slot, 0-35
     * @param stack what lies there
     */
    default boolean onInventoryShiftClick(StationView view, int slot, ItemStack stack) {
        return false;
    }

    default void onEffect(StationView view, Identifier effect, String node, CompoundTag data) {
    }

    @FunctionalInterface
    interface Factory {
        StationBehavior create(StationView view);
    }
}
