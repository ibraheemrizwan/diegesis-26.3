package com.pockyl.diegesis.impl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.event.SceneEvent;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.client.focus.FocusController;
import com.pockyl.diegesis.impl.client.scene.SceneLibrary;
import com.pockyl.diegesis.impl.network.DiegesisNetwork;
import com.pockyl.diegesis.impl.network.Packets;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The stations this client shows: the one the player works at, the ones others use nearby (the server sends their
 * state), and idle ones whose scene stays visible (found among the block entities around the player).
 */
public final class ClientStations {
    private static final ClientStations INSTANCE = new ClientStations();
    /** Stations farther than this (blocks) are dropped. */
    private static final double KEEP_RANGE = 48.0;
    /** Idle scenes are looked for this far (chunks) around the player. */
    private static final int IDLE_SCAN_CHUNKS = 2;
    private static final int IDLE_SCAN_TICKS = 20;

    private final Map<BlockPos, ClientStation> stations = new HashMap<>();
    private ClientLevel level;
    /** The station types the server has; null until it says (and on a server that never does). */
    private Set<Identifier> serverTypes;
    private int ticks;

    private ClientStations() {
    }

    public static ClientStations get() {
        return INSTANCE;
    }

    public ClientStation at(BlockPos pos) {
        return stations.get(pos);
    }

    public Collection<ClientStation> all() {
        return Collections.unmodifiableCollection(stations.values());
    }

    /** The station at {@code pos} for a type, created when missing (or replaced when the type changed). */
    public ClientStation getOrCreate(StationType type, BlockPos pos, Direction facing) {
        if (level == null || level != Minecraft.getInstance().level) {
            stations.clear();
            level = Minecraft.getInstance().level;
        }
        ClientStation station = stations.get(pos);
        if (station == null || station.type() != type) {
            station = new ClientStation(type, level, pos, facing);
            stations.put(pos.immutable(), station);
        } else {
            station.setFacing(facing);
        }
        return station;
    }

    void remove(BlockPos pos) {
        ClientStation station = stations.remove(pos);
        if (station != null) {
            station.clearRemote();
        }
    }

    public void onScenesReloaded() {
        Minecraft.getInstance().execute(() -> stations.values().forEach(ClientStation::rebuildScene));
    }

    /**
     * Whether the server this client plays on has the station type: false only once the server has said which it has and this one
     * is not among them (its addon is missing there).
     */
    public boolean serverHas(StationType type) {
        return serverTypes == null || serverTypes.contains(type.id());
    }

    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            serverTypes = null;
        }
        if (minecraft.level != level) {
            stations.clear();
            level = minecraft.level;
            FocusController.get().reset();
            HiddenBlocks.sync(java.util.Set.of());
        }
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        Iterator<ClientStation> iterator = stations.values().iterator();
        while (iterator.hasNext()) {
            ClientStation station = iterator.next();
            boolean stillThere = station.type().matches(level.getBlockState(station.pos()));
            boolean near = player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(station.pos())) < KEEP_RANGE * KEEP_RANGE;
            boolean wanted = station.isFocused() || station.isInSession() || station.isIdleVisible();
            if (!station.isFocused() && (!stillThere || !near || !wanted)) {
                station.clearRemote();
                iterator.remove();
            }
        }
        if (++ticks % IDLE_SCAN_TICKS == 0) {
            scanIdle(player);
        }
        java.util.Set<BlockPos> hidden = new java.util.HashSet<>();
        for (ClientStation station : stations.values()) {
            station.tick();
            if (station.type().hidesBlock() && station.isInSession()) {
                hidden.add(station.pos());
            }
        }
        HiddenBlocks.sync(hidden);
    }

    /** Finds block entities around the player whose station scene stays visible while idle. */
    private void scanIdle(LocalPlayer player) {
        if (StationRegistry.isEmpty()) {
            return;
        }
        ChunkPos center = player.chunkPosition();
        for (int dx = -IDLE_SCAN_CHUNKS; dx <= IDLE_SCAN_CHUNKS; dx++) {
            for (int dz = -IDLE_SCAN_CHUNKS; dz <= IDLE_SCAN_CHUNKS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(center.x() + dx, center.z() + dz, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (stations.containsKey(pos)) {
                        continue;
                    }
                    StationRegistry.find(blockEntity.getBlockState()).ifPresent(type -> {
                        SceneDefinition scene = SceneLibrary.INSTANCE.scene(type.scene());
                        if (scene != null && scene.idleVisible() && !scene.nodes().isEmpty()) {
                            Direction facing = type.orientation().resolve(blockEntity.getBlockState(), Direction.NORTH);
                            getOrCreate(type, pos, facing);
                        }
                    });
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Messages from the server
    // ---------------------------------------------------------------------------------------------------------------

    public void handle(Packets.ServerPacket packet) {
        if (packet instanceof Packets.Stations known) {
            // It comes as the player joins, maybe before the level is there.
            serverTypes = Set.copyOf(known.types());
            return;
        }
        if (level == null) {
            level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
        }
        if (packet instanceof Packets.Opened opened) {
            StationRegistry.byId(opened.type()).ifPresentOrElse(type -> {
                ClientStation station = getOrCreate(type, opened.pos(), opened.facing());
                station.state().readChanges(DiegesisNetwork.wrap(opened.state(), level.registryAccess()));
                station.setInSession(true);
                station.setSeat(opened.seat());
                FocusController.get().confirm(station);
            }, () -> {
                DiegesisMod.LOGGER.warn("Server opened unknown station {}; is the same mod installed on both sides?", opened.type());
                FocusController.get().abort(opened.pos());
            });
        } else if (packet instanceof Packets.Denied denied) {
            FocusController.get().abort(denied.pos());
        } else if (packet instanceof Packets.Closed closed) {
            FocusController.get().serverClosed(closed.pos());
            ClientStation station = stations.get(closed.pos());
            if (station != null) {
                // Also while the camera still flies back: the tick drops the station once it has landed.
                station.setInSession(false);
            }
            if (station != null && !station.isFocused()) {
                station.clearRemote();
                if (!station.isIdleVisible()) {
                    remove(closed.pos());
                }
            }
        } else if (packet instanceof Packets.StateSync sync) {
            StationRegistry.byId(sync.type()).ifPresent(type -> {
                ClientStation station = getOrCreate(type, sync.pos(), sync.facing());
                station.setInSession(true);
                station.state().readChanges(DiegesisNetwork.wrap(sync.data(), level.registryAccess()));
            });
        } else if (packet instanceof Packets.PresenceRelay relay) {
            ClientStation station = stations.get(relay.pos());
            if (station != null) {
                station.setRemote(relay.player(), relay.presence());
            }
        } else if (packet instanceof Packets.Effect effect) {
            ClientStation station = stations.get(effect.pos());
            if (station != null) {
                station.playEffect(effect.effect(), effect.node().isEmpty() ? null : effect.node(), effect.data());
            }
        }
    }

    /** Sends a scene event to the station's behaviour. */
    public static void fire(ClientStation station, SceneEvent event) {
        station.onEvent(event);
    }
}
