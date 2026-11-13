package com.pockyl.diegesis.api.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Where an item put down freely stands, in scene pixels: on a surface of the scene's {@code "placing"} or on another item.
 *
 * @param x        the middle of the item
 * @param y        the height of what it stands on
 * @param z        the middle of the item
 * @param lift     how far the item's middle is above {@code y}, with its turn and size: its lowest point touches the surface
 * @param rotation how it is turned
 * @param scale    its size, as a factor of the scene's {@code item_size}
 * @param on       the index (in the same slot group) of the item it stands on, or -1: when that one is taken away, it comes down
 */
public record ItemPose(float x, float y, float z, float lift, Quaternionfc rotation, float scale, int on) {
    /** Nothing is placed farther from the block than this, in pixels; a modified client's numbers end here. */
    private static final float MAX_DISTANCE = 256.0F;
    private static final float MIN_SCALE = 0.05F;
    private static final float MAX_SCALE = 16.0F;

    public ItemPose {
        rotation = new Quaternionf(rotation);
    }

    /** The same pose, moved up or down by {@code pixels}. */
    public ItemPose raised(float pixels) {
        return new ItemPose(x, y + pixels, z, lift, rotation, scale, on);
    }

    /** The same pose, standing on the item {@code index} (or on the surface, -1). */
    public ItemPose standingOn(int index) {
        return new ItemPose(x, y, z, lift, rotation, scale, index);
    }

    /** The height of the item's top, for what stands on it. */
    public float top() {
        return y + lift * 2.0F;
    }

    /** The pose with every number finite and in range, or null when it cannot be repaired. */
    public ItemPose sanitized() {
        Quaternionf turn = new Quaternionf(rotation);
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(lift) || !Float.isFinite(scale)
                || !turn.isFinite() || turn.lengthSquared() < 1.0e-4F) {
            return null;
        }
        return new ItemPose(Mth.clamp(x, -MAX_DISTANCE, MAX_DISTANCE), Mth.clamp(y, -MAX_DISTANCE, MAX_DISTANCE),
                Mth.clamp(z, -MAX_DISTANCE, MAX_DISTANCE), Mth.clamp(lift, 0.0F, MAX_DISTANCE), turn.normalize(),
                Mth.clamp(scale, MIN_SCALE, MAX_SCALE), on);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("x", x);
        tag.putFloat("y", y);
        tag.putFloat("z", z);
        tag.putFloat("lift", lift);
        tag.putFloat("qx", rotation.x());
        tag.putFloat("qy", rotation.y());
        tag.putFloat("qz", rotation.z());
        tag.putFloat("qw", rotation.w());
        tag.putFloat("scale", scale);
        tag.putInt("on", on);
        return tag;
    }

    /** Reads what {@link #save()} wrote; null when it makes no sense. */
    public static ItemPose load(CompoundTag tag) {
        return new ItemPose(tag.getFloatOr("x", 0.0F), tag.getFloatOr("y", 0.0F), tag.getFloatOr("z", 0.0F), tag.getFloatOr("lift", 0.0F),
                new Quaternionf(tag.getFloatOr("qx", 0.0F), tag.getFloatOr("qy", 0.0F), tag.getFloatOr("qz", 0.0F), tag.getFloatOr("qw", 0.0F)), tag.getFloatOr("scale", 0.0F),
                tag.getIntOr("on", 0)).sanitized();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(x);
        buf.writeFloat(y);
        buf.writeFloat(z);
        buf.writeFloat(lift);
        buf.writeFloat(rotation.x());
        buf.writeFloat(rotation.y());
        buf.writeFloat(rotation.z());
        buf.writeFloat(rotation.w());
        buf.writeFloat(scale);
        buf.writeVarInt(on + 1);
    }

    public static ItemPose read(FriendlyByteBuf buf) {
        float x = buf.readFloat();
        float y = buf.readFloat();
        float z = buf.readFloat();
        float lift = buf.readFloat();
        Quaternionf rotation = new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        return new ItemPose(x, y, z, lift, rotation, buf.readFloat(), buf.readVarInt() - 1);
    }
}
