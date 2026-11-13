package com.pockyl.diegesis.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import com.pockyl.diegesis.DiegesisMod;

public final class DiegesisFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DiegesisMod.init();
        FabricNetwork.register();
        ServerTickEvents.END_SERVER_TICK.register(DiegesisMod::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> DiegesisMod.onChannelReady(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> DiegesisMod.onPlayerLogout(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(DiegesisMod::onServerStopped);
    }
}
