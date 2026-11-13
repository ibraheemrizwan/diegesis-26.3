package com.pockyl.diegesis.impl.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.state.TankSpec;
import com.pockyl.diegesis.api.station.Authority;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.action.ActionHandler;
import com.pockyl.diegesis.impl.action.StationAction;
import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.impl.network.Presence;
import com.pockyl.diegesis.platform.Platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's stations in use: opens and closes them for players, applies their actions, relays what they hold and
 * ticks them. One instance per running server.
 */
public final class StationManager {
    /** Actions a player may send per second before the rest are dropped (a burst of 40 is fine). */
    private static final int ACTION_BUDGET_PER_SECOND = 40;
    private static final int PRESENCE_BUDGET_PER_SECOND = 25;

    private static StationManager instance;

    private final MinecraftServer server;
    private final Map<Key, ActiveStation> stations = new HashMap<>();
    private final Map<UUID, ActiveStation> byPlayer = new HashMap<>();
    private final Map<UUID, RateLimit> actionLimits = new HashMap<>();
    private final Map<UUID, RateLimit> presenceLimits = new HashMap<>();
    private final Map<UUID, PourBudget> pourBudgets = new HashMap<>();

    private StationManager(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized StationManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new StationManager(server);
        }
        return instance;
    }

    /** Forgets everything of a stopping server (its players are gone already). */
    public static synchronized void onServerStopped(MinecraftServer server) {
        if (instance != null && instance.server == server) {
            instance = null;
        }
    }

    public Optional<StationContext> active(ServerLevel level, BlockPos pos) {
        return Optional.ofNullable(stations.get(new Key(level.dimension(), pos)));
    }

    public void handle(ServerPlayer player, Packets.ClientPacket packet) {
        if (packet instanceof Packets.Open open) {
            if (!open(player, open.pos())) {
                Platform.INSTANCE.send(player, new Packets.Denied(open.pos()));
            }
        } else if (packet instanceof Packets.Close) {
            close(player, false);
        } else if (packet instanceof Packets.Action action) {
            handleAction(player, action);
        } else if (packet instanceof Packets.PresenceUpdate update) {
            handlePresence(player, update);
        }
    }

    /** Opens the station at {@code pos} for the player; false when it cannot be opened. */
    public boolean open(ServerPlayer player, BlockPos pos) {
        return Platform.INSTANCE.hasChannel(player) && openWithoutChannelCheck(player, pos);
    }

    /** {@link #open} for players without the library's channel: game tests (mock players) and load tests only. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public boolean openWithoutChannelCheck(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!level.isLoaded(pos) || player.isSpectator()) {
            return false;
        }
        BlockState blockState = level.getBlockState(pos);
        StationType type = StationRegistry.find(blockState).orElse(null);
        if (type == null || type.authority() != Authority.SERVER || !type.openCondition().test(player, pos, blockState)
                || !type.anchor().resolve(level, pos, blockState).equals(pos)) {
            return false;
        }
        double reach = type.reach();
        if (player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > reach * reach) {
            return false;
        }
        Key key = new Key(level.dimension(), pos.immutable());
        ActiveStation station = stations.get(key);
        if (station != null && station == byPlayer.get(player.getUUID())) {
            return true;
        }
        if (station != null && (station.type() != type || !type.shared() && !station.isEmpty())) {
            return false;
        }
        close(player, true);
        if (station == null) {
            Direction towardsPlayer = Direction.getApproximateNearest(player.getX() - pos.getX() - 0.5, 0.0, player.getZ() - pos.getZ() - 0.5);
            if (!towardsPlayer.getAxis().isHorizontal()) {
                towardsPlayer = player.getDirection().getOpposite();
            }
            station = new ActiveStation(type, level, key.pos(), type.orientation().resolve(blockState, towardsPlayer));
            stations.put(key, station);
        }
        byPlayer.put(player.getUUID(), station);
        station.addUser(player);
        return true;
    }

    /** Ends the player's use of their station, if any. {@code notify} tells their client (it did not ask itself). */
    public void close(ServerPlayer player, boolean notify) {
        ActiveStation station = byPlayer.remove(player.getUUID());
        if (station == null) {
            return;
        }
        if (station.removeUser(player, notify)) {
            station.end();
            stations.values().remove(station);
        }
    }

    public void sendStationTypes(ServerPlayer player) {
        if (Platform.INSTANCE.hasChannel(player)) {
            Platform.INSTANCE.send(player, new Packets.Stations(StationRegistry.all().stream().map(StationType::id).toList()));
        }
    }

    public void onLogout(ServerPlayer player) {
        close(player, false);
        actionLimits.remove(player.getUUID());
        presenceLimits.remove(player.getUUID());
        pourBudgets.remove(player.getUUID());
    }

    public void tick() {
        if (stations.isEmpty()) {
            return;
        }
        for (ActiveStation station : List.copyOf(stations.values())) {
            for (ServerPlayer player : station.invalidUsers()) {
                close(player, true);
            }
            if (stations.containsValue(station)) {
                try {
                    station.tick();
                } catch (RuntimeException e) {
                    // A broken station logic must not take the server down: end the station and say why.
                    DiegesisMod.LOGGER.error("Station {} failed and was closed", station, e);
                    for (ServerPlayer player : new ArrayList<>(station.users())) {
                        close(player, true);
                    }
                }
            }
        }
        long second = server.getTickCount() / 20L;
        actionLimits.values().forEach(limit -> limit.reset(second));
        presenceLimits.values().forEach(limit -> limit.reset(second));
    }

    /** Number of stations in use, for tests and diagnostics. */
    public int activeCount() {
        return stations.size();
    }

    private void handleAction(ServerPlayer player, Packets.Action packet) {
        ActiveStation station = byPlayer.get(player.getUUID());
        if (station == null || !station.pos().equals(packet.pos())) {
            return;
        }
        // The block may have gone in this tick, before the station's own check: an action would act on a block entity that is gone.
        if (!station.canStay(player)) {
            return;
        }
        if (!actionLimits.computeIfAbsent(player.getUUID(), uuid -> new RateLimit(ACTION_BUDGET_PER_SECOND)).take()) {
            return;
        }
        StationAction action = packet.action();
        if (action instanceof StationAction.Pour pour) {
            // Pouring is paced: at most the tanks' pour rate, with up to a second's worth saved up.
            int allowed = pourBudget(player, station, pour);
            if (allowed <= 0) {
                return;
            }
            action = new StationAction.Pour(pour.from(), pour.to(), Math.min(pour.amount(), allowed));
        }
        boolean accepted;
        try {
            accepted = ActionHandler.apply(station, station.logic(), player, action);
            if (accepted && action instanceof StationAction.Pour pour) {
                spendPour(player, pour.amount());
            }
        } catch (RuntimeException e) {
            DiegesisMod.LOGGER.error("Action {} at station {} failed", action, station, e);
            accepted = false;
        }
        if (!accepted) {
            // The client predicted it; send it the truth (station and inventory) so it snaps back.
            station.state().markAllDirty();
            station.flush();
            player.inventoryMenu.sendAllDataToRemote();
        } else {
            station.flush();
            player.inventoryMenu.broadcastChanges();
        }
    }

    private void handlePresence(ServerPlayer player, Packets.PresenceUpdate update) {
        ActiveStation station = byPlayer.get(player.getUUID());
        Presence presence = update.presence();
        if (station == null || !station.pos().equals(update.pos()) || !presence.isPlausible()) {
            return;
        }
        if (!presenceLimits.computeIfAbsent(player.getUUID(), uuid -> new RateLimit(PRESENCE_BUDGET_PER_SECOND)).take()) {
            return;
        }
        Presence relayed = presence;
        int slot = presence.inventorySlot();
        if (slot >= 0) {
            // Only what the player really holds is shown to the others.
            net.minecraft.world.item.ItemStack held = slot == Presence.CARRIED_SLOT ? player.inventoryMenu.getCarried()
                    : slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE ? player.getInventory().getItem(slot)
                    : net.minecraft.world.item.ItemStack.EMPTY;
            relayed = presence.withCarried(held.isEmpty() ? held : held.copyWithCount(1));
        }
        station.relayPresence(player, relayed);
    }

    private int pourBudget(ServerPlayer player, ActiveStation station, StationAction.Pour pour) {
        TankSpec from = station.type().schema().tanks().get(pour.from());
        TankSpec to = station.type().schema().tanks().get(pour.to());
        if (from == null || to == null) {
            return 0;
        }
        int rate = Math.min(from.pourRate(), to.pourRate());
        PourBudget budget = pourBudgets.computeIfAbsent(player.getUUID(), uuid -> new PourBudget());
        long now = server.getTickCount();
        budget.millibuckets = Math.min(rate, budget.millibuckets + (now - budget.tick) * rate / 20.0);
        budget.tick = now;
        return (int) budget.millibuckets;
    }

    private void spendPour(ServerPlayer player, int amount) {
        PourBudget budget = pourBudgets.get(player.getUUID());
        if (budget != null) {
            budget.millibuckets -= amount;
        }
    }

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /** Millibuckets a player may still pour, refilled at the tanks' pour rate. */
    private static final class PourBudget {
        private double millibuckets = 0.0;
        private long tick;
    }

    /** A per-second budget of messages. */
    private static final class RateLimit {
        private final int budget;
        private int left;
        private long second = -1;

        RateLimit(int budget) {
            this.budget = budget;
            this.left = budget;
        }

        boolean take() {
            if (left <= 0) {
                return false;
            }
            left--;
            return true;
        }

        void reset(long now) {
            if (now != second) {
                second = now;
                left = budget;
            }
        }
    }
}
