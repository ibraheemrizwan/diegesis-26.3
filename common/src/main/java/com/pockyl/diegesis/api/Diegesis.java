package com.pockyl.diegesis.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.server.StationManager;

import java.util.Optional;

/**
 * The library's entry point for common code (both sides). Client-only parts are in
 * {@link com.pockyl.diegesis.api.client.DiegesisClient}.
 *
 * <p>Diegesis turns a block's GUI into an interface inside the world: when a player uses the block, the camera moves to
 * it and the player works with the block's parts directly - drags items onto it, carries objects, turns knobs. A mod
 * describes such an interface as a {@link StationType} (code: which blocks, what state, what rules) and a scene
 * (a JSON file: what is drawn where, what can be touched).
 */
public final class Diegesis {
    public static final String MOD_ID = "diegesis";

    private Diegesis() {
    }

    /** Starts a station type; finish it with {@link StationType.Builder#register()}. */
    public static StationType.Builder station(Identifier id) {
        return StationType.builder(id);
    }

    /** Registers a station type. Call it during mod setup, the same on both sides. */
    public static StationType register(StationType type) {
        return StationRegistry.register(type);
    }

    public static Optional<StationType> stationType(Identifier id) {
        return StationRegistry.byId(id);
    }

    /** The station a block state opens, if any. */
    public static Optional<StationType> stationFor(BlockState state) {
        return StationRegistry.find(state);
    }

    /**
     * Opens the station at {@code pos} for a player from server code, e.g. from your block's {@code use} method when
     * you want to decide yourself when it opens. Returns false when the block is not a station, the player is too far
     * or their client lacks the library (then show your usual GUI).
     */
    public static boolean open(ServerPlayer player, BlockPos pos) {
        return StationManager.get(player.level().getServer()).open(player, pos);
    }

    /** Ends the player's work at whatever station they use. */
    public static void close(ServerPlayer player) {
        StationManager.get(player.level().getServer()).close(player, true);
    }

    /** A station currently in use at {@code pos}. */
    public static Optional<StationContext> activeStation(ServerLevel level, BlockPos pos) {
        return StationManager.get(level.getServer()).active(level, pos);
    }
}
