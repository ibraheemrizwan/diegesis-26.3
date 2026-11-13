package com.pockyl.diegesis.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import com.pockyl.diegesis.impl.network.DiegesisNetwork;
import com.pockyl.diegesis.impl.network.Packets;

/**
 * The library's payloads on Fabric; optional on both sides (a side without them simply cannot send them). Fabric API runs
 * the handlers on the main thread.
 */
final class FabricNetwork {
    private FabricNetwork() {
    }

    /** Both sides: the payload types and the server's receiver. */
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(DiegesisNetwork.ToServer.TYPE, DiegesisNetwork.ToServer.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DiegesisNetwork.ToClient.TYPE, DiegesisNetwork.ToClient.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DiegesisNetwork.ToServer.TYPE, (payload, context) -> {
            if (payload.packet() != null) {
                DiegesisNetwork.receiveOnServer(context.player(), payload.packet());
            }
        });
    }

    /** Client side; only called on clients. */
    static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(DiegesisNetwork.ToClient.TYPE, (payload, context) -> {
            if (payload.packet() != null) {
                DiegesisNetwork.receiveOnClient(payload.packet());
            }
        });
    }

    static net.minecraft.resources.Identifier channel() {
        return DiegesisNetwork.ToClient.TYPE.id();
    }

    static boolean canSend(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, DiegesisNetwork.ToClient.TYPE);
    }

    static void send(ServerPlayer player, Packets.ServerPacket packet) {
        ServerPlayNetworking.send(player, new DiegesisNetwork.ToClient(packet));
    }

    /** Client side; only called on clients. */
    static boolean serverHasChannel() {
        return ClientPlayNetworking.canSend(DiegesisNetwork.ToServer.TYPE);
    }

    /** Client side; only called on clients. */
    static void sendToServer(Packets.ClientPacket packet) {
        ClientPlayNetworking.send(new DiegesisNetwork.ToServer(packet));
    }
}
