package com.pockyl.diegesis.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.platform.Platform;

public final class FabricPlatform implements Platform {
    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean hasChannel(ServerPlayer player) {
        return FabricNetwork.canSend(player);
    }

    @Override
    public void send(ServerPlayer player, Packets.ServerPacket packet) {
        if (hasChannel(player)) {
            FabricNetwork.send(player, packet);
        }
    }
}
