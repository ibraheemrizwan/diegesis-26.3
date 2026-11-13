package com.pockyl.diegesis.platform;

import net.minecraft.server.level.ServerPlayer;

import com.pockyl.diegesis.impl.network.Packets;

import java.util.ServiceLoader;

/**
 * What the common code needs from the mod loader, on both sides. Each loader project provides one implementation,
 * listed in its {@code META-INF/services}.
 */
public interface Platform {
    Platform INSTANCE = load(Platform.class);

    boolean isModLoaded(String modId);

    /** Whether the player's client has the library's channel. */
    boolean hasChannel(ServerPlayer player);

    void send(ServerPlayer player, Packets.ServerPacket packet);

    static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + type.getName() + " for this mod loader"));
    }
}
