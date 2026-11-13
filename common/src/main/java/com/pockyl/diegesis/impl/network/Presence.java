package com.pockyl.diegesis.impl.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/**
 * What a working player is holding right now, so the players around see it move: a grabbed node or an item carried
 * from the inventory, with its pose in the station's own space (pixels, degrees, before the station's facing turn).
 *
 * @param node          the grabbed node, or empty for none
 * @param inventorySlot the inventory slot of a carried item (client to server), or -1
 * @param carried       the carried item (server to clients)
 * @param tilt          how the grabbed node is tipped: axis times degrees, zero when upright
 * @param turn          how far it is turned about its own axis, degrees
 * @param pourInto      the node it pours into, or empty
 * @param flow          how much runs, 0 to 1
 */
public record Presence(String node, int inventorySlot, ItemStack carried, Vector3f pos, Vector3f rotation, Vector3f tilt, float turn,
        String pourInto, float flow) {
    public static final Presence NONE = holding("", -1, ItemStack.EMPTY, new Vector3f(), new Vector3f());
    /** {@code inventorySlot} value meaning "the stack on my cursor". */
    public static final int CARRIED_SLOT = 99;
    private static final float MAX_COORDINATE = 256.0F;

    /** Something held that does not pour. */
    public static Presence holding(String node, int inventorySlot, ItemStack carried, Vector3f pos, Vector3f rotation) {
        return new Presence(node, inventorySlot, carried, pos, rotation, new Vector3f(), 0.0F, "", 0.0F);
    }

    /** The same with the item the server knows the player carries. */
    public Presence withCarried(ItemStack item) {
        return new Presence(node, -1, item, pos, rotation, tilt, turn, pourInto, flow);
    }

    public boolean isEmpty() {
        return node.isEmpty() && inventorySlot < 0 && carried.isEmpty();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(node, 64);
        buf.writeVarInt(inventorySlot + 1);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, carried);
        buf.writeVector3f(pos);
        buf.writeVector3f(rotation);
        buf.writeVector3f(tilt);
        buf.writeFloat(turn);
        buf.writeUtf(pourInto, 64);
        buf.writeFloat(flow);
    }

    public static Presence read(RegistryFriendlyByteBuf buf) {
        return new Presence(buf.readUtf(64), buf.readVarInt() - 1, ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVector3f(), buf.readVector3f(),
                buf.readVector3f(), buf.readFloat(), buf.readUtf(64), buf.readFloat());
    }

    /** Whether the numbers are sane (finite, near the block): what the server relays. */
    public boolean isPlausible() {
        return finite(pos) && finite(rotation) && finite(tilt) && Float.isFinite(turn) && flow >= 0.0F && flow <= 1.0F
                && Math.abs(pos.x) < MAX_COORDINATE && Math.abs(pos.y) < MAX_COORDINATE
                && Math.abs(pos.z) < MAX_COORDINATE;
    }

    private static boolean finite(Vector3f vector) {
        return Float.isFinite(vector.x) && Float.isFinite(vector.y) && Float.isFinite(vector.z);
    }
}
