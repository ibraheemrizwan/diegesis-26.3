package com.pockyl.diegesis.impl.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.impl.server.StationManager;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Entry and exit of every message: the loaders move the two payloads ({@link ToServer}, {@link ToClient}) and call
 * {@link #receiveOnServer} / {@link #receiveOnClient} on the main thread.
 */
public final class DiegesisNetwork {
    public static final String PROTOCOL_VERSION = "2";

    /** Set by the client part of the library; a dedicated server never loads client classes through this. */
    private static volatile Consumer<Packets.ServerPacket> clientHandler = packet -> {
    };

    private DiegesisNetwork() {
    }

    public static void setClientHandler(Consumer<Packets.ServerPacket> handler) {
        clientHandler = handler;
    }

    public static void receiveOnServer(ServerPlayer player, Packets.ClientPacket packet) {
        StationManager.get(player.level().getServer()).handle(player, packet);
    }

    public static void receiveOnClient(Packets.ServerPacket packet) {
        clientHandler.accept(packet);
    }

    /** Encodes into a fresh byte array, e.g. a state shared by several messages. */
    public static byte[] toBytes(RegistryAccess registries, Consumer<RegistryFriendlyByteBuf> writer) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    public static RegistryFriendlyByteBuf wrap(byte[] bytes, RegistryAccess registries) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
    }

    /**
     * A message whose body could not be read is dropped, not answered with a disconnect: the rest of the bytes are skipped
     * and the handler sees null.
     */
    private static <P, T> StreamCodec<RegistryFriendlyByteBuf, T> codec(Function<RegistryFriendlyByteBuf, P> read,
            Function<P, T> wrap, Function<T, Packets.Packet> unwrap) {
        return StreamCodec.of((buf, payload) -> unwrap.apply(payload).write(buf), buf -> {
            try {
                return wrap.apply(read.apply(buf));
            } catch (RuntimeException e) {
                DiegesisMod.LOGGER.warn("Dropped a malformed Diegesis message", e);
                buf.skipBytes(buf.readableBytes());
                return wrap.apply(null);
            }
        });
    }

    /** Client to server; {@code packet} is null when it could not be read. */
    public record ToServer(Packets.ClientPacket packet) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(DiegesisMod.id("to_server"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToServer> STREAM_CODEC = codec(Packets::readClient, ToServer::new,
                ToServer::packet);

        @Override
        public Type<ToServer> type() {
            return TYPE;
        }
    }

    /** Server to client; {@code packet} is null when it could not be read. */
    public record ToClient(Packets.ServerPacket packet) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(DiegesisMod.id("to_client"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToClient> STREAM_CODEC = codec(Packets::readServer, ToClient::new,
                ToClient::packet);

        @Override
        public Type<ToClient> type() {
            return TYPE;
        }
    }
}
