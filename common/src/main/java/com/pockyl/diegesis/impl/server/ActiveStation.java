package com.pockyl.diegesis.impl.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.state.StationHost;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.network.DiegesisNetwork;
import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.impl.network.Presence;
import com.pockyl.diegesis.platform.Platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A station in use on the server: its users, its state (a host block entity's, or one for this use only), its logic,
 * and the players around it who see it ("watchers"). Created when the first player opens the block, dropped when the
 * last one leaves.
 */
final class ActiveStation implements StationContext {
    /** Players this close (blocks) see the station's state and the objects being carried. */
    static final double WATCH_RANGE = 32.0;
    private static final int WATCH_REFRESH_TICKS = 10;

    private final StationType type;
    private final ServerLevel level;
    private final BlockPos pos;
    private final Direction facing;
    private final StationState state;
    private final boolean hosted;
    private final StationLogic logic;
    private final List<ServerPlayer> users = new ArrayList<>();
    private final Set<UUID> subscribers = new HashSet<>();
    private final List<ServerPlayer> watchers = new ArrayList<>();
    private final java.util.Map<UUID, String> seats = new java.util.HashMap<>();
    private final java.util.Map<UUID, SeatMount> mounts = new java.util.HashMap<>();
    private int age;

    ActiveStation(StationType type, ServerLevel level, BlockPos pos, Direction facing) {
        this.type = type;
        this.level = level;
        this.pos = pos.immutable();
        this.facing = facing;
        StationState hostState = null;
        if (level.getBlockEntity(pos) instanceof StationHost host) {
            hostState = host.stationState();
            if (hostState.schema() != type.schema()) {
                DiegesisMod.LOGGER.warn("Block entity at {} keeps a state of another schema than station {}; using a temporary one", pos,
                        type.id());
                hostState = null;
            }
        }
        this.hosted = hostState != null;
        this.state = hosted ? hostState : new StationState(type.schema());
        this.logic = type.logic().create(this);
        if (!hosted && keepsLooseItems()) {
            DiegesisMod.LOGGER.warn("Station {} keeps items but the block entity at {} is not a StationHost and no storage is bound:"
                    + " they are lost on close", type.id(), pos);
        }
    }

    /** Whether something is kept between uses that nothing here can keep: logic may bind kept slots to a storage. */
    private boolean keepsLooseItems() {
        return type.schema().slotGroups().values().stream().anyMatch(group -> !group.rules().returnOnClose() && !state.isBound(group.name()))
                || type.schema().tanks().values().stream().anyMatch(com.pockyl.diegesis.api.state.TankSpec::keep);
    }

    @Override
    public StationType type() {
        return type;
    }

    @Override
    public Level level() {
        return level;
    }

    ServerLevel serverLevel() {
        return level;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    @Override
    public BlockState blockState() {
        return level.getBlockState(pos);
    }

    @Override
    public Direction facing() {
        return facing;
    }

    @Override
    public StationState state() {
        return state;
    }

    @Override
    public Collection<ServerPlayer> users() {
        return Collections.unmodifiableList(users);
    }

    @Override
    public java.util.Optional<String> seatOf(Player player) {
        return java.util.Optional.ofNullable(seats.get(player.getUUID()));
    }

    StationLogic logic() {
        return logic;
    }

    /** Gives a newcomer the free seat nearest to them, and sits them down there. */
    private void takeSeat(ServerPlayer player) {
        type.nearestSeat(pos, facing, player.position(), seat -> !seats.containsValue(seat.id())).ifPresent(seat -> {
            seats.put(player.getUUID(), seat.id());
            if (seat.sit()) {
                SeatMount mount = SeatMount.sit(level, player, seat.worldPosition(pos, facing));
                if (mount != null) {
                    mounts.put(player.getUUID(), mount);
                }
            }
        });
    }

    private void leaveSeat(ServerPlayer player) {
        seats.remove(player.getUUID());
        SeatMount mount = mounts.remove(player.getUUID());
        if (mount != null) {
            mount.leave(player);
        }
    }

    boolean isEmpty() {
        return users.isEmpty();
    }

    @Override
    public void effect(Identifier effect, String node, CompoundTag data) {
        Packets.Effect packet = new Packets.Effect(pos, effect, node == null ? "" : node, data);
        forEachRecipient(player -> Platform.INSTANCE.send(player, packet));
    }

    void addUser(ServerPlayer player) {
        boolean first = users.isEmpty();
        users.add(player);
        takeSeat(player);
        if (first) {
            logic.updateOutputs(this);
        }
        logic.onOpen(this, player);
        subscribers.add(player.getUUID());
        // What onOpen changed goes out first: the snapshot below forgets what is pending, and the others would never hear of it.
        flush();
        byte[] full = DiegesisNetwork.toBytes(level.registryAccess(), buf -> state.writeChanges(buf, true));
        Platform.INSTANCE.send(player, new Packets.Opened(pos, type.id(), facing, full, seats.getOrDefault(player.getUUID(), "")));
        refreshWatchers();
    }

    /** Removes a user; returns whether the station is now unused. */
    boolean removeUser(ServerPlayer player, boolean notify) {
        if (!users.remove(player)) {
            return users.isEmpty();
        }
        relayPresence(player, Presence.NONE);
        logic.onClose(this, player);
        leaveSeat(player);
        // The cursor stack always goes back. When the last user leaves, the slots that are not kept give their items back
        // and the tanks that are not kept start over - also on a block entity's station, which saves only the kept ones.
        com.pockyl.diegesis.impl.action.ActionHandler.returnItems(this, logic, player, users.isEmpty());
        player.inventoryMenu.broadcastChanges();
        flush();
        if (notify && Platform.INSTANCE.hasChannel(player)) {
            Platform.INSTANCE.send(player, new Packets.Closed(pos));
        }
        return users.isEmpty();
    }

    /** Called when the station ends: tells the watchers to forget its temporary state. */
    void end() {
        Packets.Closed closed = new Packets.Closed(pos);
        for (ServerPlayer watcher : watchers) {
            if (!users.contains(watcher)) {
                Platform.INSTANCE.send(watcher, closed);
            }
        }
        subscribers.clear();
        watchers.clear();
    }

    void tick() {
        age++;
        logic.tick(this);
        if (age % WATCH_REFRESH_TICKS == 0) {
            refreshWatchers();
        }
        flush();
    }

    /** Sends what changed to everyone subscribed; newcomers get everything. */
    void flush() {
        state.pollStorages();
        boolean dirty = state.isDirty();
        if (dirty) {
            byte[] delta = DiegesisNetwork.toBytes(level.registryAccess(), buf -> state.writeChanges(buf, false));
            Packets.StateSync packet = new Packets.StateSync(pos, type.id(), facing, false, delta);
            forEachRecipient(player -> {
                if (subscribers.contains(player.getUUID())) {
                    Platform.INSTANCE.send(player, packet);
                }
            });
            if (hosted) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    blockEntity.setChanged();
                    BlockState blockState = blockEntity.getBlockState();
                    level.sendBlockUpdated(pos, blockState, blockState, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                }
            }
        }
        List<ServerPlayer> newcomers = new ArrayList<>();
        forEachRecipient(player -> {
            if (!subscribers.contains(player.getUUID())) {
                newcomers.add(player);
            }
        });
        if (!newcomers.isEmpty()) {
            byte[] full = DiegesisNetwork.toBytes(level.registryAccess(), buf -> state.writeChanges(buf, true));
            Packets.StateSync packet = new Packets.StateSync(pos, type.id(), facing, true, full);
            for (ServerPlayer player : newcomers) {
                subscribers.add(player.getUUID());
                Platform.INSTANCE.send(player, packet);
            }
        }
    }

    void relayPresence(ServerPlayer from, Presence presence) {
        Packets.PresenceRelay packet = new Packets.PresenceRelay(pos, from.getUUID(), presence);
        forEachRecipient(player -> {
            if (player != from) {
                Platform.INSTANCE.send(player, packet);
            }
        });
    }

    /** Whether a user may still work here: alive, in the level, in reach, and the block is still this station. */
    boolean canStay(ServerPlayer player) {
        double reach = type.reach() + 1.5;
        return player.isAlive() && !player.hasDisconnected() && player.level() == level && !player.isSpectator()
                && player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= reach * reach && type.matches(level.getBlockState(pos));
    }

    List<ServerPlayer> invalidUsers() {
        List<ServerPlayer> invalid = new ArrayList<>();
        for (ServerPlayer player : users) {
            if (!canStay(player)) {
                invalid.add(player);
            }
        }
        return invalid;
    }

    private void refreshWatchers() {
        watchers.clear();
        double rangeSq = WATCH_RANGE * WATCH_RANGE;
        for (ServerPlayer player : level.players()) {
            if (!users.contains(player) && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= rangeSq && Platform.INSTANCE.hasChannel(player)) {
                watchers.add(player);
            }
        }
        subscribers.removeIf(uuid -> users.stream().noneMatch(p -> p.getUUID().equals(uuid))
                && watchers.stream().noneMatch(p -> p.getUUID().equals(uuid)));
    }

    private void forEachRecipient(java.util.function.Consumer<ServerPlayer> action) {
        for (ServerPlayer user : users) {
            action.accept(user);
        }
        for (ServerPlayer watcher : watchers) {
            if (!users.contains(watcher) && !watcher.hasDisconnected()) {
                action.accept(watcher);
            }
        }
    }

    @Override
    public String toString() {
        return "ActiveStation[" + type.id() + " at " + pos + ", " + users.size() + " users]";
    }

    static boolean isUser(ActiveStation station, Player player) {
        return station.users.contains(player);
    }
}
