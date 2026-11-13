package com.pockyl.diegesis;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.impl.server.StationManager;

/**
 * Internal entry point of the library: the loaders call these hooks. Mods use {@link Diegesis} and
 * {@link com.pockyl.diegesis.api.client.DiegesisClient} instead.
 */
public final class DiegesisMod {
    public static final Logger LOGGER = LogUtils.getLogger();

    private DiegesisMod() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Diegesis.MOD_ID, path);
    }

    public static void init() {
        LOGGER.debug("Diegesis ready");
    }

    /** End of every server tick. */
    public static void onServerTick(MinecraftServer server) {
        StationManager.get(server).tick();
    }

    /** A player's client has the library's channel: it hears which stations this server has. */
    public static void onChannelReady(ServerPlayer player) {
        StationManager.get(player.level().getServer()).sendStationTypes(player);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        StationManager.get(player.level().getServer()).onLogout(player);
    }

    public static void onServerStopped(MinecraftServer server) {
        StationManager.onServerStopped(server);
    }
}
