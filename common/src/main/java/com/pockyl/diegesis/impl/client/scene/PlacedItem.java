package com.pockyl.diegesis.impl.client.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.impl.client.anim.Easing;

/**
 * What a node of a placed slot shows: the slot's item where its pose says. The node's matrix follows the pose with a short
 * ease, so that a turn glides and what loses its support comes down; an item that just arrived drops the last bit.
 */
public final class PlacedItem {
    private static final float EASE = 0.035F;
    /** How far (pixels) an item drops when it is put down, and in how many seconds. */
    private static final float DROP = 1.2F;
    private static final float DROP_TIME = 0.13F;
    private static final float HOVER_LIFT = 0.35F;
    /** How far (pixels) below the top of a burning item its flame starts. */
    private static final float FLAME_DEPTH = 0.5F;

    private final SlotRef slot;
    private final PlacingOptions options;
    private ItemStack stack = ItemStack.EMPTY;
    private PlacedShape shape;
    private ItemPose pose;
    private Box collider;
    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private float size;
    private float dropLeft;
    private float burning;

    PlacedItem(SlotRef slot, PlacingOptions options) {
        this.slot = slot;
        this.options = options;
    }

    public SlotRef slot() {
        return slot;
    }

    public ItemStack stack() {
        return stack;
    }

    public PlacedShape shape() {
        return shape;
    }

    /** Where the item stands; null while the slot is empty. */
    public ItemPose pose() {
        return stack.isEmpty() ? null : pose;
    }

    public PlacingOptions options() {
        return options;
    }

    Box collider() {
        return collider;
    }

    /** Takes the slot's item and pose over; returns whether an item stands there. */
    boolean sync(StationState state) {
        ItemStack now = state.getItem(slot);
        ItemPose wanted = state.getPose(slot);
        boolean arrived = stack.isEmpty() && !now.isEmpty();
        if (now.isEmpty()) {
            stack = ItemStack.EMPTY;
            return false;
        }
        if (!ItemStack.matches(stack, now) || shape == null) {
            stack = now;
            shape = PlacedShape.of(now, Minecraft.getInstance().level, options.blockModels());
            Vector3fc half = shape.half();
            collider = new Box(new Vector3f(half).negate(), new Vector3f(half));
        }
        pose = wanted != null ? wanted : Placing.fallback(options, slot.index(), shape);
        if (arrived) {
            position.set(pose.x(), pose.y() + pose.lift(), pose.z());
            rotation.set(pose.rotation());
            size = pose.scale();
            dropLeft = DROP_TIME;
        }
        return true;
    }

    /** Writes the node's matrices for this frame. */
    void update(float dt, float hover, Matrix4f matrix, Matrix4f pickMatrix) {
        if (stack.isEmpty()) {
            return;
        }
        position.x = Easing.approach(position.x, pose.x(), EASE, dt);
        position.y = Easing.approach(position.y, pose.y() + pose.lift(), EASE * 2.0F, dt);
        position.z = Easing.approach(position.z, pose.z(), EASE, dt);
        rotation.slerp(pose.rotation(), 1.0F - (float) Math.pow(0.5, dt / EASE)).normalize();
        size = Easing.approach(size, pose.scale(), EASE, dt);
        dropLeft = Math.max(0.0F, dropLeft - dt);
        float fall = dropLeft / DROP_TIME;
        float scale = shape.modelScale(size * options.itemSize());
        matrix.translation(position.x, position.y + DROP * fall * fall + HOVER_LIFT * hover, position.z).rotate(rotation).scale(scale);
        // The hit box stays where the pose is: it never slips from under the cursor by what hovering does.
        pickMatrix.translation(pose.x(), pose.y() + pose.lift(), pose.z()).rotate(pose.rotation()).scale(shape.modelScale(pose.scale() * options.itemSize()));
    }

    /** Counts the time for a burning item's next flame; true when one is due. */
    public boolean burns(float dt) {
        if (stack.isEmpty() || !shape.hasFlame() || !options.itemLight()) {
            return false;
        }
        burning += dt;
        if (burning < 0.12F) {
            return false;
        }
        burning = 0.0F;
        return true;
    }

    /** Where the flame sits: the top of the item, in scene pixels. */
    public Vector3f flamePosition() {
        Vector3f top = new Vector3f(0.0F, shape.half().y() * shape.modelScale(size * options.itemSize()) - FLAME_DEPTH, 0.0F);
        return rotation.transform(top).add(position);
    }
}
