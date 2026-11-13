package com.pockyl.diegesis.api.station;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.TankContents;

/**
 * The rules of a station: what it accepts, what it computes, what its controls do. Runs where the station's state lives
 * (the server for {@link Authority#SERVER}). The library has already checked an action against the schema (slot exists,
 * item fits, value in range) before it asks the logic; every method has a harmless default.
 *
 * <p>One logic object is created per station in use (see {@link StationType.Builder#logic}), so it may keep fields
 * for that use; what must outlive it belongs in the {@code StationState} of a {@code StationHost}.
 */
public interface StationLogic {
    StationLogic NONE = new StationLogic() {
    };

    /** A player started working at the station. */
    default void onOpen(StationContext station, Player player) {
    }

    /** A player stopped working at the station (left, walked away, logged out). */
    default void onClose(StationContext station, Player player) {
    }

    /** Whether {@code stack} may be put into {@code slot}; the slot rules were already checked. */
    default boolean mayPlace(StationContext station, Player player, SlotRef slot, ItemStack stack) {
        return true;
    }

    /** Whether the player may take the item out of {@code slot}. */
    default boolean mayTake(StationContext station, Player player, SlotRef slot) {
        return true;
    }

    /**
     * Recomputes the outputs from the slots, e.g. looks up a recipe and calls {@code state().setOutput}. Called after
     * every slot change and when the station opens.
     */
    default void updateOutputs(StationContext station) {
    }

    /**
     * A player takes an output: consume what it costs and return the item they get, or {@link ItemStack#EMPTY} when they
     * cannot have it. {@link #updateOutputs} runs afterwards.
     */
    default ItemStack takeOutput(StationContext station, Player player, String output) {
        return ItemStack.EMPTY;
    }

    /**
     * A player took {@code stack} out of a slot, onto the cursor or into the inventory: the hook for what a vanilla result
     * slot does on taking (experience, recipes, advancements).
     */
    default void onTaken(StationContext station, Player player, SlotRef slot, ItemStack stack) {
    }

    /** A slot's item changed (by a player or by code). */
    default void onSlotChanged(StationContext station, SlotRef slot) {
    }

    /** A player turned or resized an item that stands freely in a {@code placed()} group. */
    default void onPoseChanged(StationContext station, Player player, SlotRef slot, ItemPose pose) {
    }

    /** A player set a control to a new value (already clamped to its range). */
    default void onControlChanged(StationContext station, Player player, String control, float value) {
    }

    /** A player pressed a button control. */
    default void onButton(StationContext station, Player player, String control) {
    }

    /**
     * Whether {@code liquid} may be poured from one tank into another; the tank specs (capacity, accepted liquids) were
     * already checked.
     */
    default boolean mayPour(StationContext station, Player player, String from, String to, TankContents liquid) {
        return true;
    }

    /** Liquid was poured: react here (mix into something new with {@code state().setTank}, play an effect). */
    default void onPoured(StationContext station, Player player, String from, String to, TankContents poured) {
    }

    /** A mod-defined action sent with {@code StationView.sendAction} on the client. Check {@code data} like any input. */
    default void onCustomAction(StationContext station, Player player, String action, CompoundTag data) {
    }

    /** Every tick while at least one player works at the station. */
    default void tick(StationContext station) {
    }

    /** Creates the logic of one station in use. */
    @FunctionalInterface
    interface Factory {
        StationLogic create(StationContext station);
    }
}
