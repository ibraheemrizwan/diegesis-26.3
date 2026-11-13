package com.pockyl.diegesis.impl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.pockyl.diegesis.api.station.Authority;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.client.focus.FocusController;
import com.pockyl.diegesis.impl.network.DiegesisNetwork;
import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.platform.ClientPlatform;

/** The client half of the library: wiring called by the loaders, and the hook that turns a block use into a station. */
public final class DiegesisClientRuntime {
    private DiegesisClientRuntime() {
    }

    public static void init() {
        DiegesisNetwork.setClientHandler(packet -> ClientStations.get().handle(packet));
    }

    /** End of every client tick. */
    public static void tick() {
        ClientStations.get().tick();
    }

    /**
     * The player used a block (right click). Returns {@link InteractionResult#SUCCESS} when it opened a station - the
     * use is then not sent to the server, so the block's own GUI does not open - or null to let the use go on.
     */
    public static InteractionResult onUseBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || StationRegistry.isEmpty()) {
            return null;
        }
        FocusController focus = FocusController.get();
        BlockPos pos = hit.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        StationType type = StationRegistry.find(state).orElse(null);
        if (type != null) {
            // A part of a multiblock opens the station of its main block.
            BlockPos main = type.anchor().resolve(player.level(), pos, state);
            if (!main.equals(pos)) {
                pos = main.immutable();
                state = player.level().getBlockState(pos);
                if (!type.matches(state) || !type.anchor().resolve(player.level(), pos, state).equals(pos)) {
                    return InteractionResult.CONSUME;
                }
            }
        }
        if (focus.isActive()) {
            // While the camera is at or flying back from a station, a use must never reach a station block (its vanilla
            // GUI would open); the same station opens again, another one waits until the camera is back.
            boolean again = focus.isExiting() && type != null && focus.station() != null && focus.station().pos().equals(pos);
            if (!again) {
                return type != null ? InteractionResult.CONSUME : null;
            }
        }
        if (type == null || !mayOpen(player, type, pos, state)) {
            return null;
        }
        boolean server = type.authority() == Authority.SERVER;
        ClientStation station = prepare(player, type, pos, state);
        if (server) {
            ClientPlatform.INSTANCE.sendToServer(new Packets.Open(pos));
        }
        FocusController.get().begin(station, server);
        return InteractionResult.SUCCESS;
    }

    /**
     * Whether the player may open {@code type}'s station at {@code pos} now: its condition, the reach from where they stand,
     * and a server that has the library (else the block keeps its vanilla behaviour).
     */
    public static boolean mayOpen(LocalPlayer player, StationType type, BlockPos pos, BlockState state) {
        if (player.isSpectator() || !type.openCondition().test(player, pos, state)) {
            return false;
        }
        if (player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > type.reach() * type.reach()) {
            return false;
        }
        return type.authority() != Authority.SERVER || ClientPlatform.INSTANCE.serverHasChannel() && ClientStations.get().serverHas(type);
    }

    /** The client's station at {@code pos}, facing as it opens for the player, in the seat they would likely get. */
    private static ClientStation prepare(LocalPlayer player, StationType type, BlockPos pos, BlockState state) {
        Direction towardsPlayer = Direction.getApproximateNearest(player.getX() - pos.getX() - 0.5, 0.0, player.getZ() - pos.getZ() - 0.5);
        if (!towardsPlayer.getAxis().isHorizontal()) {
            towardsPlayer = player.getDirection().getOpposite();
        }
        ClientStation station = ClientStations.get().getOrCreate(type, pos, type.orientation().resolve(state, towardsPlayer));
        // Fly towards the likely seat at once; the server's answer settles it.
        station.setSeat(type.nearestSeat(pos, station.facing(), player.position(), seat -> true).map(seat -> seat.id()).orElse(null));
        return station;
    }

    /**
     * Goes over from the station worked at to the one at {@code pos} without leaving: the camera flies from one to the other
     * over {@code seconds}. False when that station cannot be opened now.
     */
    public static boolean goOver(BlockPos pos, float seconds) {
        LocalPlayer player = Minecraft.getInstance().player;
        FocusController focus = FocusController.get();
        if (player == null || !focus.isInteractive() && !focus.isGoingOver()) {
            return false;
        }
        BlockState state = player.level().getBlockState(pos);
        StationType type = StationRegistry.find(state).orElse(null);
        if (type == null || !type.anchor().resolve(player.level(), pos, state).equals(pos) || !mayOpen(player, type, pos, state)) {
            return false;
        }
        boolean server = type.authority() == Authority.SERVER;
        if (focus.station().type().authority() == Authority.SERVER && !server) {
            ClientPlatform.INSTANCE.sendToServer(new Packets.Close());
        }
        ClientStation station = prepare(player, type, pos, state);
        if (server) {
            // The server ends the use of the station left as it opens this one.
            ClientPlatform.INSTANCE.sendToServer(new Packets.Open(pos));
        }
        focus.goOver(station, server, seconds);
        return true;
    }

    /** Whether the camera is taken over this frame; the camera hook asks first. */
    public static boolean isCameraActive() {
        return FocusController.get().isActive() && Minecraft.getInstance().player != null;
    }
}
