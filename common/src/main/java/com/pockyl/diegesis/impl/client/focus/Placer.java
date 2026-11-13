package com.pockyl.diegesis.impl.client.focus;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.scene.PlaceSurface;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.StationSchema.SlotGroup;
import com.pockyl.diegesis.impl.action.StationAction;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;
import com.pockyl.diegesis.impl.client.scene.PlacedItem;
import com.pockyl.diegesis.impl.client.scene.PlacedShape;
import com.pockyl.diegesis.impl.client.scene.Placing;
import com.pockyl.diegesis.impl.client.scene.SceneInstance;

/**
 * The hand at a scene with free placing: where the stack on the cursor would stand, how it is turned, and the turning of items
 * that stand already. The preview is worked out anew each frame from the cursor; nothing depends on where something was drawn.
 */
final class Placer {
    private final Quaternionf turn = new Quaternionf();
    private float scale = 1.0F;
    private Item held;
    private ItemPose preview;
    private FocusInput.StationRay aimedRay;

    /** The scene's placing, or null. */
    static PlacingOptions options(ClientStation station) {
        return station == null || station.scene() == null ? null : station.scene().definition().placing().orElse(null);
    }

    /** Where the stack in the hand would stand now, or null when it is not over a place. */
    ItemPose preview() {
        return preview;
    }

    void reset() {
        turn.identity();
        scale = 1.0F;
        held = null;
        preview = null;
        aimedRay = null;
    }

    /** The item taken from {@code pose} stays as it was turned while it is in the hand. */
    void adopt(ItemStack stack, ItemPose pose) {
        turn.set(pose.rotation());
        scale = pose.scale();
        held = stack.getItem();
    }

    /**
     * @param ray    the cursor ray
     * @param frozen the hand is turning the stack: it stays where it was
     */
    void update(ClientStation station, FocusInput.StationRay ray, ItemStack carried, boolean overWorld, boolean frozen) {
        preview = null;
        PlacingOptions options = options(station);
        if (options == null || carried.isEmpty() || !overWorld) {
            return;
        }
        if (carried.getItem() != held) {
            turn.identity();
            scale = 1.0F;
            held = carried.getItem();
        }
        if (freeSlot(station, options, carried) == null) {
            return;
        }
        if (!frozen || aimedRay == null) {
            aimedRay = ray;
        }
        PlacedShape shape = PlacedShape.of(carried, Minecraft.getInstance().level, options.blockModels());
        preview = options.stacking() ? onAnItem(station, options, shape, aimedRay) : null;
        if (preview == null) {
            Placing.Aim aim = Placing.aim(options, aimedRay.origin(), aimedRay.direction());
            if (aim != null) {
                preview = Placing.standing(options, shape, aim.surface(), aim.x(), aim.surface().height(), aim.z(), turn, scale, -1);
            }
        }
    }

    private ItemPose onAnItem(ClientStation station, PlacingOptions options, PlacedShape shape, FocusInput.StationRay ray) {
        SceneInstance.Hit hit = station.scene().pick(ray.origin(), ray.direction(), node -> node.placed() != null);
        ItemPose below = hit == null ? null : hit.node().placed().pose();
        if (below == null || !Placing.isOnTop(below, hit.point().y)) {
            return null;
        }
        PlaceSurface surface = Placing.surfaceOf(options, below);
        if (surface == null) {
            return null;
        }
        float room = surface.height() + surface.clearance() - below.top();
        if (Placing.fittingScale(options, shape, turn, scale, room) < options.scaleRange()[0]) {
            return null;
        }
        return Placing.standing(options, shape, surface, below.x(), below.top(), below.z(), turn, scale, hit.node().placed().slot().index());
    }

    /** The first slot of the group that is free and takes {@code stack}, or null. */
    private static SlotRef freeSlot(ClientStation station, PlacingOptions options, ItemStack stack) {
        SlotGroup group = station.type().schema().slotGroups().get(options.group());
        if (group == null || !group.rules().accepts().test(stack)) {
            return null;
        }
        for (int i = 0; i < group.size(); i++) {
            if (station.state().getItem(group.ref(i)).isEmpty()) {
                return group.ref(i);
            }
        }
        return null;
    }

    /** Puts the stack down where the preview shows it; false when there is no place. */
    boolean place(ClientStation station, ItemStack carried) {
        SlotRef slot = preview == null ? null : freeSlot(station, options(station), carried);
        return slot != null && station.perform(new StationAction.Place(slot, preview));
    }

    /** One notch of the wheel: turns about the vertical, with {@code tilt} about the view's sideways axis; with {@code size}, grows. */
    private Quaternionf notched(PlacingOptions options, Quaternionfc rotation, double amount, boolean tilt, Vector3fc right) {
        float angle = (float) Math.toRadians(options.turnStep()) * (float) Math.signum(amount);
        Quaternionf step = tilt ? new Quaternionf().rotateAxis(angle, right.x(), right.y(), right.z()) : new Quaternionf().rotateY(angle);
        return step.mul(rotation).normalize();
    }

    private static float grown(PlacingOptions options, float value, double amount) {
        float next = value + options.scaleStep() * (float) Math.signum(amount);
        return Mth.clamp(Math.round(next / options.scaleStep()) * options.scaleStep(), options.scaleRange()[0], options.scaleRange()[1]);
    }

    /** The wheel on the stack in the hand. */
    void scrollCarried(PlacingOptions options, double amount, boolean tilt, boolean size, Vector3fc right) {
        if (size) {
            scale = grown(options, scale, amount);
        } else {
            turn.set(notched(options, turn, amount, tilt, right));
        }
    }

    /** The wheel on an item that stands. */
    void scrollPlaced(ClientStation station, NodeInstance node, double amount, boolean tilt, boolean size, Vector3fc right) {
        PlacingOptions options = options(station);
        ItemPose pose = node.placed().pose();
        if (pose == null) {
            return;
        }
        Quaternionfc rotation = size ? pose.rotation() : notched(options, pose.rotation(), amount, tilt, right);
        apply(station, node.placed(), rotation, size ? grown(options, pose.scale(), amount) : pose.scale(), true);
    }

    /** A drag turning the stack in the hand: sideways about the vertical, up and down about the view's sideways axis. */
    void dragCarried(PlacingOptions options, float dx, float dy, Vector3fc right) {
        turn.set(dragged(options, turn, dx, dy, right));
    }

    /** A drag turning an item that stands; {@code send} tells the server, otherwise it only moves here for now. */
    void dragPlaced(ClientStation station, NodeInstance node, float dx, float dy, Vector3fc right, boolean send) {
        ItemPose pose = node.placed().pose();
        if (pose != null) {
            apply(station, node.placed(), dragged(options(station), pose.rotation(), dx, dy, right), pose.scale(), send);
        }
    }

    /** Sends where the item ended up. */
    void commit(ClientStation station, NodeInstance node) {
        ItemPose pose = node.placed().pose();
        if (pose != null) {
            apply(station, node.placed(), pose.rotation(), pose.scale(), true);
        }
    }

    private static Quaternionf dragged(PlacingOptions options, Quaternionfc rotation, float dx, float dy, Vector3fc right) {
        float speed = (float) Math.toRadians(options.turnSpeed());
        return new Quaternionf().rotateY(dx * speed).rotateAxis(dy * speed, right.x(), right.y(), right.z()).mul(rotation).normalize();
    }

    /** Gives a standing item a new turn and size: it settles on its lowest point, and what stands on it goes up or down with it. */
    private static void apply(ClientStation station, PlacedItem item, Quaternionfc rotation, float scale, boolean send) {
        PlacingOptions options = item.options();
        ItemPose before = item.pose();
        PlaceSurface surface = Placing.surfaceOf(options, before);
        ItemPose after = surface != null
                ? Placing.standing(options, item.shape(), surface, before.x(), before.y(), before.z(), rotation, scale, before.on())
                : new ItemPose(before.x(), before.y(), before.z(), item.shape().extents(rotation, options.itemSize() * scale).y, rotation, scale, before.on());
        set(station, item.slot(), after, send);
        carryAbove(station, options, item.slot(), after.top() - before.top(), send);
    }

    private static void carryAbove(ClientStation station, PlacingOptions options, SlotRef below, float pixels, boolean send) {
        if (Math.abs(pixels) < 1.0e-4F) {
            return;
        }
        SlotGroup group = station.type().schema().slotGroups().get(below.group());
        for (int i = 0; i < group.size(); i++) {
            ItemPose pose = station.state().getPose(group.ref(i));
            if (pose != null && pose.on() == below.index() && !station.state().getItem(group.ref(i)).isEmpty()) {
                set(station, group.ref(i), pose.raised(pixels), send);
                carryAbove(station, options, group.ref(i), pixels, send);
            }
        }
    }

    private static void set(ClientStation station, SlotRef slot, ItemPose pose, boolean send) {
        if (send) {
            station.perform(new StationAction.Pose(slot, pose));
        } else {
            station.state().setPose(slot, pose);
        }
    }
}
