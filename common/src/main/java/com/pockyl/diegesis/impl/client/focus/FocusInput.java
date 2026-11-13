package com.pockyl.diegesis.impl.client.focus;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.event.SceneEvent;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.CameraGlance;
import com.pockyl.diegesis.api.scene.CarryPlane;
import com.pockyl.diegesis.api.scene.ControlBinding;
import com.pockyl.diegesis.api.scene.Grabbable;
import com.pockyl.diegesis.api.scene.InterfaceOptions;
import com.pockyl.diegesis.api.scene.InventoryDrawer;
import com.pockyl.diegesis.api.scene.InventoryLayout;
import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.scene.SlotBinding;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.api.scene.Transform;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.api.state.TankSpec;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.state.ControlSpec;
import com.pockyl.diegesis.api.station.Authority;
import com.pockyl.diegesis.impl.action.StationAction;
import com.pockyl.diegesis.impl.action.StationAction.ClickKind;
import com.pockyl.diegesis.impl.action.StationAction.Target;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;
import com.pockyl.diegesis.impl.client.anim.Easing;
import com.pockyl.diegesis.impl.client.anim.Spring;
import com.pockyl.diegesis.impl.client.fx.PourStreams;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;
import com.pockyl.diegesis.impl.client.scene.SceneInstance;
import com.pockyl.diegesis.impl.liquid.LiquidVolume;
import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.impl.network.Presence;
import com.pockyl.diegesis.platform.ClientPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What the mouse does at a station. Items work like a vanilla container screen - the cursor carries a stack; left and
 * right clicks pick up, put down, split and swap; shift-click moves between the station and the inventory; a double
 * click gathers; dragging a carried stack over slots spreads it - whether the slot is in the inventory panel or on the
 * block. A stack may also be dragged out of a slot and let go over another. Nodes with other roles are operated directly:
 * grabbable objects are carried, by a drag or from one click to the next, controls are clicked, dragged or scrolled.
 * Works in station space (pixels, before the facing turn) on rays from the {@link Projector}.
 */
public final class FocusInput {
    private static final FocusInput INSTANCE = new FocusInput();
    /** Same as vanilla's double click window. */
    private static final long DOUBLE_CLICK_MS = 250L;
    /** GUI pixels of mouse travel that turn a knob through its whole range. */
    private static final double KNOB_TRAVEL = 160.0;
    /** GUI pixels round a knob's centre where the mouse does not turn it (the angle jumps there). */
    private static final double KNOB_DEAD_ZONE = 4.0;
    private static final long CONTROL_SEND_INTERVAL_MS = 60L;
    private static final long PRESENCE_INTERVAL_MS = 50L;
    /** Even without a margin, nothing is carried farther than this from the block (a ray grazing the plane). */
    private static final float FAR_LIMIT = 128.0F;
    /** How high (pixels) a carried item floats over the work surface, and over a slot it sticks to. */
    private static final float STICK_HEIGHT = 1.4F;
    /** Nearer to the camera than this (pixels) the stack in the hand would fill the screen: there it is an icon at the cursor. */
    private static final float NEAREST_CARRY = 22.0F;
    /** Shorter and stiller than this, a press on an object is a click: the object stays in the hand. */
    private static final long QUICK_CLICK_MS = 220L;
    private static final double CLICK_TRAVEL = 5.0;

    private NodeInstance hovered;
    private int hoveredInventory = -1;
    /** The inventory slot laid out in the scene under the cursor, or -1. */
    private int sceneInventory = -1;
    private Gesture gesture;
    /** The stroke whose END the behaviour is hearing now, for {@link #holdStroke}. */
    private Stroke ending;
    private double mouseX;
    private double mouseY;
    private long lastPresence;
    private boolean lastReportedCarry;

    // Vanilla-style click state.
    private final List<Target> spreadTargets = new ArrayList<>();
    private int spreadButton = -1;
    private Target lastClicked;
    private int lastButton;
    private long lastClickTime;

    // The carried stack's 3D pose.
    private final Spring carry = new Spring(0.03F);
    private final Vector3f carryTarget = new Vector3f();
    private final Vector3f lean = new Vector3f();
    private boolean carryInWorld;
    private boolean carryPlaced;
    private float carrySize = 4.5F;
    private long lastFrame = Util.getNanos();
    private float frameDt;

    // A laid-out inventory that shows on demand (InventoryLayout.Show.TOGGLE).
    private boolean inventoryOpen;
    private float inventoryReveal;

    /** A stack just taken with this press: letting go over another slot puts it there. */
    private Target dragFrom;
    private int dragButton;
    private double dragX;
    private double dragY;

    private final Drawer drawer = new Drawer();
    private final Glance glance = new Glance();
    private final Neighbors neighbors = new Neighbors();
    private final Gains gains = new Gains();
    private final Placer placer = new Placer();

    private FocusInput() {
    }

    public static FocusInput get() {
        return INSTANCE;
    }

    public NodeInstance hovered() {
        return hovered;
    }

    /** The inventory slot under the cursor: on the screen panel or laid out in the scene; -1 for none. */
    public int hoveredInventorySlot() {
        return hoveredInventory >= 0 ? hoveredInventory : sceneInventory;
    }

    /** The carried stack, for drawing; null when nothing is carried. */
    public Carried carried() {
        ItemStack stack = carriedStack();
        if (stack.isEmpty()) {
            return null;
        }
        return new Carried(stack, carry.value(), lean, carryInWorld, carrySize, mouseX, mouseY, placer.preview());
    }

    /** Whether a slot is one of those the current drag spreads over. */
    public boolean isSpreadTarget(Target target) {
        return spreadTargets.size() > 1 && spreadTargets.contains(target);
    }

    Drawer drawer() {
        return drawer;
    }

    Neighbors neighbors() {
        return neighbors;
    }

    Gains gains() {
        return gains;
    }

    Glance look() {
        return glance;
    }

    /** {@code StationView.glance}. */
    public boolean glance(String id) {
        ClientStation station = station();
        CameraGlance wanted = station == null ? null : FocusController.get().rig().glance(id).orElse(null);
        if (wanted == null) {
            return false;
        }
        CameraGlance before = glance.current();
        glance.lookAt(wanted);
        reportGlance(station, before);
        return true;
    }

    /** {@code StationView.glanceBack}. */
    public void glanceBack() {
        CameraGlance before = glance.current();
        glance.lookBack();
        reportGlance(station(), before);
    }

    /** The id of the glance the camera is at or on its way to, or null. */
    public String currentGlance() {
        CameraGlance current = glance.current();
        return current == null ? null : current.id();
    }

    private void reportGlance(ClientStation station, CameraGlance before) {
        CameraGlance now = glance.current();
        if (station == null || now == before) {
            return;
        }
        if (before != null) {
            station.onEvent(new SceneEvent.Glance(before.id(), false));
        }
        if (now != null) {
            station.onEvent(new SceneEvent.Glance(now.id(), true));
        }
    }

    /** Tab: shows or hides an inventory laid out with {@code "show": "toggle"} or pins a drawer; false when the scene has none. */
    public boolean toggleInventory() {
        ClientStation station = station();
        if (station != null && station.scene() != null && station.scene().definition().inventoryDrawer().isPresent()) {
            if (!station.scene().definition().inventoryDrawer().get().alwaysOpen()) {
                drawer.togglePin();
            }
            return true;
        }
        InventoryLayout layout = station == null || station.scene() == null ? null : station.scene().inventoryLayout();
        if (layout == null || layout.show() != InventoryLayout.Show.TOGGLE) {
            return false;
        }
        inventoryOpen = !inventoryOpen;
        if (!inventoryOpen && hoveredInventory == -1) {
            sceneInventory = -1;
        }
        return true;
    }

    /** Whether the laid-out inventory takes clicks now. */
    public boolean isInventoryShown() {
        ClientStation station = station();
        InventoryLayout layout = station == null || station.scene() == null ? null : station.scene().inventoryLayout();
        return layout != null && (layout.show() == InventoryLayout.Show.ALWAYS || inventoryOpen || !carriedStack().isEmpty());
    }

    /** Whether the scene's inventory is out: the drawer, or a laid-out one. */
    public boolean isInventoryOpen() {
        return drawer.isOpen() || isInventoryShown();
    }

    /** {@code StationView.setInventoryOpen}. */
    public void setInventoryOpen(boolean open) {
        drawer.setPinned(open);
        inventoryOpen = open;
    }

    /** How far the laid-out inventory has come up, 0 (hidden) to 1, for its animation. */
    public float inventoryReveal() {
        return inventoryReveal;
    }

    /** Whether the player holds nothing: no stack on the cursor, nothing carried, no control in hand. */
    public boolean isHandFree() {
        return gesture == null && carriedStack().isEmpty();
    }

    public boolean isBusy() {
        return gesture != null || spreadButton >= 0;
    }

    /** Whether the hand is on something in the scene: a carried object, a control being turned. */
    boolean isHoldingObject() {
        return gesture != null;
    }

    /** The tank the held vessel pours into now, or null. */
    public NodeInstance pourTarget() {
        return gesture instanceof NodeGrab grab ? grab.pourTarget : null;
    }

    /** The tanks that would take what the held vessel holds. */
    public List<NodeInstance> pourCandidates() {
        return gesture instanceof NodeGrab grab ? grab.candidates : List.of();
    }

    /** How full the tank being poured into is, for the bar next to the cursor; null when nothing is poured. */
    Meter pourMeter() {
        return gesture instanceof NodeGrab grab ? grab.meter() : null;
    }

    /** Ends whatever is in progress: a carried object flies home, a drag is dropped. */
    public void cancel() {
        // Not reset: the camera comes back from a glance on its way out, without a jump.
        glanceBack();
        if (gesture != null) {
            gesture.cancel();
            gesture = null;
            sendPresence(Presence.NONE, true);
        }
        spreadTargets.clear();
        spreadButton = -1;
        dragFrom = null;
        carryPlaced = false;
        sceneInventory = -1;
        inventoryOpen = false;
        inventoryReveal = 0.0F;
        drawer.reset();
        placer.reset();
        setHovered(null);
    }

    private static ItemStack carriedStack() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.inventoryMenu.getCarried();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mouse (from FocusScreen; inventorySlot: -1 not on the panel, -2 on the panel between slots)
    // ---------------------------------------------------------------------------------------------------------------

    void mouseMoved(double x, double y, int inventorySlot) {
        mouseX = x;
        mouseY = y;
        hoveredInventory = inventorySlot;
    }

    void press(double x, double y, int button, boolean shift, int inventorySlot) {
        mouseMoved(x, y, inventorySlot);
        ClientStation station = station();
        if (gesture instanceof NodeGrab grab && grab.isKept() || gesture instanceof Stroke stroke && stroke.held) {
            // The object stayed in the hand after a click, or the stroke went on: the next click lets it go.
            finishGesture();
            return;
        }
        if (station == null || gesture != null || spreadButton >= 0 || inventorySlot == -2) {
            return;
        }
        if (inventorySlot == -1 && pressOnPlacing(station, x, y, button)) {
            return;
        }
        Target target = inventorySlot >= 0 ? new Target.Inventory(inventorySlot) : null;
        NodeInstance node = null;
        if (target == null) {
            // With a stack in the hand only what takes items counts: a zone or a control in front must not swallow the click.
            // Shift reaches through a zone for strokes too, to the slot behind it, as shift-clicks are about items.
            boolean holding = !carriedStack().isEmpty();
            if (holding && offerCarried(station, button, shift)) {
                return;
            }
            Picked picked = pickAll(station, candidate -> isClickable(candidate)
                    && (holding ? itemTarget(station, candidate.definition()) != null : !shift || !candidate.definition().strokes()));
            node = picked.node();
            target = picked.inventorySlot() >= 0 ? new Target.Inventory(picked.inventorySlot())
                    : node == null ? null : itemTarget(station, node.definition());
        }
        if (target != null) {
            clickItem(station, target, button, shift, node);
            return;
        }
        if (button == 1 && !carriedStack().isEmpty() && station.scene().definition().putAway()) {
            // A right click nothing took puts the stack in the hand away, a knife after cutting.
            station.putAwayCarried();
            return;
        }
        if (node == null || !carriedStack().isEmpty()) {
            return;
        }
        NodeDefinition definition = node.definition();
        if (definition.control().isPresent()) {
            startControl(station, node, definition.control().get(), x, y);
        } else if (definition.grabbable().isPresent()) {
            gesture = new NodeGrab(station, node, definition.grabbable().get(), x, y);
            UiSounds.grab(node.worldPosition());
            station.onEvent(new SceneEvent.Grab(node.id()));
        } else if (definition.strokes()) {
            gesture = new Stroke(station, node, button, false);
        } else {
            station.onEvent(new SceneEvent.Click(node.id(), button));
        }
    }

    /** Lets the behaviour use the stack on the cursor on the node under it; true when it did. */
    private boolean offerCarried(ClientStation station, int button, boolean shift) {
        NodeInstance node = pickAll(station, candidate -> candidate.isEnabled() && !candidate.definition().trigger()).node();
        return node != null && station.itemClick(node.id(), carriedStack(), button, shift);
    }

    /** Where the cursor points on the level plane at {@code height}, in the station's pixels. */
    public java.util.Optional<Vector3f> cursorOn(ClientStation station, float height) {
        return planeAt(stationRay(station, mouseX, mouseY), height);
    }

    /** Where a point of the screen, in fractions of its size, lies on the level plane at {@code height}. */
    public java.util.Optional<Vector3f> screenOn(ClientStation station, float x, float y, float height) {
        Minecraft minecraft = Minecraft.getInstance();
        return planeAt(stationRay(station, x * minecraft.getWindow().getGuiScaledWidth(), y * minecraft.getWindow().getGuiScaledHeight()), height);
    }

    private static java.util.Optional<Vector3f> planeAt(StationRay ray, float height) {
        float t = Math.abs(ray.direction.y) < 1.0e-5F ? -1.0F : (height - ray.origin.y) / ray.direction.y;
        return t <= 0.0F ? java.util.Optional.empty() : java.util.Optional.of(new Vector3f(ray.direction).mul(t).add(ray.origin));
    }

    /** Where the cursor points on the plane through a node at {@code height} over its origin, in the node's pixels. */
    public java.util.Optional<Vector3f> pointerOn(ClientStation station, NodeInstance node, float height) {
        StationRay ray = stationRay(station, mouseX, mouseY);
        Matrix4f inverse = new Matrix4f(node.pickMatrix()).invert();
        Vector3f origin = inverse.transformPosition(new Vector3f(ray.origin));
        Vector3f direction = inverse.transformDirection(new Vector3f(ray.direction));
        float t = Math.abs(direction.y) < 1.0e-5F ? -1.0F : (height - origin.y) / direction.y;
        return t <= 0.0F ? java.util.Optional.empty() : java.util.Optional.of(new Vector3f(direction).mul(t).add(origin));
    }

    /** With free placing, the left button puts the stack in the hand down; the right one, held, turns it or the item it is on. */
    private boolean pressOnPlacing(ClientStation station, double x, double y, int button) {
        if (Placer.options(station) == null) {
            return false;
        }
        if (!carriedStack().isEmpty()) {
            if (placer.preview() == null) {
                return false;
            }
            if (button == 1) {
                gesture = new Turn(station, null, x, y);
            } else {
                placeCarried(station);
            }
            return true;
        }
        NodeInstance node = button == 1 ? pickAll(station, this::isClickable).node() : null;
        if (node == null || node.placed() == null) {
            return false;
        }
        gesture = new Turn(station, node, x, y);
        return true;
    }

    private void placeCarried(ClientStation station) {
        ItemPose pose = placer.preview();
        if (placer.place(station, carriedStack())) {
            UiSounds.putDown(station.toWorld(pose.x(), pose.y(), pose.z()));
        }
    }

    private void clickItem(ClientStation station, Target target, int button, boolean shift, NodeInstance node) {
        ItemStack carried = carriedStack();
        long now = Util.getMillis();
        boolean doubleClick = target.equals(lastClicked) && button == lastButton && now - lastClickTime < DOUBLE_CLICK_MS;
        lastClicked = target;
        lastButton = button;
        lastClickTime = now;
        Vec3 at = node != null ? node.worldPosition() : Vec3.atCenterOf(station.pos());
        if (shift && target instanceof Target.Inventory inventory && carried.isEmpty()
                && station.inventoryShiftClick(inventory.index(), itemAt(station, target))) {
            return;
        }
        if (shift) {
            if (station.perform(new StationAction.Click(target, button, ClickKind.QUICK_MOVE))) {
                UiSounds.pickUp(at);
            }
        } else if (doubleClick && button == 0 && !carried.isEmpty() && !(target instanceof Target.Output)) {
            station.perform(new StationAction.Click(target, 0, ClickKind.PICKUP_ALL));
            lastClicked = null;
        } else if (carried.isEmpty() || target instanceof Target.Output) {
            boolean had = !itemAt(station, target).isEmpty();
            if (had && node != null && node.placed() != null && node.placed().pose() != null) {
                placer.adopt(node.placed().stack(), node.placed().pose());
            }
            if (station.perform(new StationAction.Click(target, button, ClickKind.PICKUP)) && had) {
                UiSounds.pickUp(at);
                snapCarryTo(station, target, node);
                dragFrom = target;
                dragButton = button;
                dragX = mouseX;
                dragY = mouseY;
            }
        } else {
            // Holding a stack: decide on release whether it was a click on one slot or a drag over several.
            spreadButton = button;
            spreadTargets.clear();
            spreadTargets.add(target);
        }
    }

    void drag(double x, double y, int inventorySlot) {
        mouseMoved(x, y, inventorySlot);
        ClientStation station = station();
        if (station == null) {
            return;
        }
        if (gesture != null) {
            gesture.drag(x, y);
            return;
        }
        if (spreadButton < 0) {
            return;
        }
        Target target = inventorySlot >= 0 ? new Target.Inventory(inventorySlot) : null;
        if (target == null && inventorySlot == -1) {
            Picked picked = pickAll(station, FocusInput::isFixedSlot);
            if (picked.inventorySlot() >= 0) {
                target = new Target.Inventory(picked.inventorySlot());
            } else if (picked.node() != null) {
                target = new Target.Station(picked.node().definition().slot().get().slot());
            }
        }
        ItemStack carried = carriedStack();
        if (target == null || spreadTargets.contains(target) || carried.getCount() <= spreadTargets.size()) {
            return;
        }
        ItemStack there = itemAt(station, target);
        if (there.isEmpty() || ItemStack.isSameItemSameComponents(there, carried)) {
            spreadTargets.add(target);
        }
    }

    void release(double x, double y, int button, int inventorySlot) {
        mouseMoved(x, y, inventorySlot);
        ClientStation station = station();
        if (gesture != null) {
            if (gesture instanceof NodeGrab grab && grab.keepAfterRelease(x, y)) {
                return;
            }
            finishGesture();
            return;
        }
        if (station != null && dragFrom != null && button == dragButton) {
            dropDragged(station, x, y, inventorySlot);
        }
        if (station == null || spreadButton < 0 || button != spreadButton) {
            return;
        }
        if (spreadTargets.size() > 1) {
            if (station.perform(new StationAction.Spread(spreadTargets, spreadButton))) {
                UiSounds.putDown(Vec3.atCenterOf(station.pos()));
            }
        } else if (spreadTargets.size() == 1) {
            Target target = spreadTargets.get(0);
            if (station.perform(new StationAction.Click(target, spreadButton, ClickKind.PICKUP))) {
                UiSounds.putDown(target instanceof Target.Station && hovered != null ? hovered.worldPosition() : Vec3.atCenterOf(station.pos()));
            }
        }
        spreadTargets.clear();
        spreadButton = -1;
    }

    private void finishGesture() {
        Gesture finished = gesture;
        gesture = null;
        finished.release();
        sendPresence(Presence.NONE, true);
    }

    /** A stack taken with this press and dragged away goes into the slot it is let go over, or stays on the cursor. */
    private void dropDragged(ClientStation station, double x, double y, int inventorySlot) {
        Target from = dragFrom;
        dragFrom = null;
        if (Math.hypot(x - dragX, y - dragY) < CLICK_TRAVEL || carriedStack().isEmpty()) {
            return;
        }
        if (inventorySlot == -1 && placer.preview() != null) {
            placeCarried(station);
            return;
        }
        if (inventorySlot == -1 && offerCarried(station, dragButton, false)) {
            return;
        }
        Target to = inventorySlot >= 0 ? new Target.Inventory(inventorySlot) : null;
        NodeInstance node = null;
        if (to == null && inventorySlot == -1) {
            Picked picked = pickAll(station, candidate -> isFixedSlot(candidate) || candidate.definition().dropZone().isPresent() && isClickable(candidate));
            node = picked.node();
            to = picked.inventorySlot() >= 0 ? new Target.Inventory(picked.inventorySlot())
                    : node == null ? null : itemTarget(station, node.definition());
        }
        if (to != null && !to.equals(from) && station.perform(new StationAction.Click(to, dragButton, ClickKind.PICKUP))) {
            UiSounds.putDown(node != null ? node.worldPosition() : Vec3.atCenterOf(station.pos()));
        }
    }

    void scroll(double x, double y, double amount) {
        mouseMoved(x, y, hoveredInventory);
        ClientStation station = station();
        if (gesture instanceof NodeGrab grab) {
            grab.scroll(amount);
            return;
        }
        if (station == null || gesture != null || hoveredInventory != -1) {
            return;
        }
        if (scrollOnPlacing(station, amount)) {
            return;
        }
        SceneInstance.Hit hit = pick(station, this::isClickable);
        if (hit == null) {
            return;
        }
        NodeInstance node = hit.node();
        node.definition().control().ifPresent(control -> {
            ControlSpec spec = station.type().schema().controls().get(control.control());
            if (spec == null || spec.isButton() || spec.isLocked() || control.type() == ControlBinding.Type.BUTTON) {
                return;
            }
            float step = spec.steps() > 0 ? (spec.max() - spec.min()) / spec.steps() : (spec.max() - spec.min()) / 20.0F;
            float value = spec.sanitize(station.state().getControl(control.control()) + (float) Math.signum(amount) * step);
            setControl(station, node, control, spec, value, true);
        });
    }

    /** The wheel turns what is in the hand or under the cursor; with Shift it tips it towards the player, with Control it sizes it. */
    private boolean scrollOnPlacing(ClientStation station, double amount) {
        PlacingOptions options = Placer.options(station);
        if (options == null) {
            return false;
        }
        boolean tilt = FocusScreen.shift();
        boolean size = FocusScreen.control();
        Vector3f right = screenRight(station);
        if (placer.preview() != null) {
            placer.scrollCarried(options, amount, tilt, size, right);
        } else if (hovered != null && hovered.placed() != null) {
            placer.scrollPlaced(station, hovered, amount, tilt, size, right);
        } else {
            return false;
        }
        UiSounds.detent(Vec3.atCenterOf(station.pos()), 0.5F);
        return true;
    }

    /** The axis that runs to the right of the screen, in the station's space: the same wherever the cursor is. */
    private static Vector3f screenRight(ClientStation station) {
        var window = Minecraft.getInstance().getWindow();
        return new Vector3f(stationRay(station, window.getGuiScaledWidth() / 2.0, window.getGuiScaledHeight() / 2.0).direction)
                .cross(0.0F, 1.0F, 0.0F).normalize();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Per frame
    // ---------------------------------------------------------------------------------------------------------------

    /** Called by the renderer once per frame, right after the camera was captured. */
    public void frameFromRenderer() {
        long now = Util.getNanos();
        float dt = Math.min((now - lastFrame) / 1.0e9F, 0.1F);
        lastFrame = now;
        ClientStation station = station();
        if (station == null || station.scene() == null || !Projector.isValid()) {
            return;
        }
        frameDt = dt;
        watchGains(station, dt);
        float revealTarget = isInventoryShown() ? 1.0F : 0.0F;
        inventoryReveal += Math.signum(revealTarget - inventoryReveal) * Math.min(Math.abs(revealTarget - inventoryReveal), dt * 4.0F);
        updateGlance(station, dt);
        if (updateNeighbors(station, dt)) {
            return;
        }
        if (gesture != null) {
            gesture.frame();
            if (gesture != null && station.type().authority() == Authority.SERVER) {
                Presence presence = gesture.presence();
                if (presence != null) {
                    sendPresence(presence, false);
                }
            }
        }
        boolean carrying = !carriedStack().isEmpty();
        if (gesture == null || gesture instanceof NodeGrab) {
            Predicate<NodeInstance> filter = carrying
                    ? candidate -> isFixedSlot(candidate) || candidate.definition().dropZone().isPresent() && isClickable(candidate) : this::isClickable;
            Picked picked = hoveredInventory != -1 ? Picked.NONE : pickAll(station, filter);
            sceneInventory = picked.inventorySlot();
            setHovered(picked.node());
        }
        updateCarry(station, dt, carrying);
        reportCarry(station);
        if (gesture == null && !carrying && hovered != null && hovered.definition().strokes()) {
            station.onEvent(new SceneEvent.Stroke(hovered.id(), SceneEvent.Stroke.Phase.HOVER, -1, strokePoint(station, hovered)));
        }
    }

    /** Where the cursor is on the upright plane through a node's origin that faces the camera. */
    private Vector3f strokePoint(ClientStation station, NodeInstance node) {
        StationRay ray = stationRay(station, mouseX, mouseY);
        var window = Minecraft.getInstance().getWindow();
        Vector3f facing = new Vector3f(stationRay(station, window.getGuiScaledWidth() / 2.0, window.getGuiScaledHeight() / 2.0).direction);
        facing.y = 0.0F;
        facing.normalize();
        Vector3f origin = node.matrix().transformPosition(new Vector3f());
        float along = ray.direction.dot(facing);
        float t = Math.abs(along) < 1.0e-5F ? 0.0F : new Vector3f(origin).sub(ray.origin).dot(facing) / along;
        return new Vector3f(ray.direction).mul(t).add(ray.origin);
    }

    private void updateGlance(ClientStation station, float dt) {
        List<CameraGlance> glances = station.scene().definition().camera().glances();
        if (glances.isEmpty()) {
            return;
        }
        var window = Minecraft.getInstance().getWindow();
        float u = (float) (mouseX / window.getGuiScaledWidth());
        float v = (float) (mouseY / window.getGuiScaledHeight());
        boolean free = !isBusy() && hoveredInventory == -1 && !drawer.isOpen() && !FocusScreen.isCovered();
        CameraGlance before = glance.current();
        glance.watch(glances, u, v, dt, free, carriedStack());
        reportGlance(station, before);
    }

    /** What came into the inventory at this station flies in from the station's middle on the screen. */
    private void watchGains(ClientStation station, float dt) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        org.joml.Vector3fc target = FocusController.get().baseTarget();
        org.joml.Vector2f from = Projector.toScreen(station.toWorld(target.x(), target.y(), target.z()));
        var window = Minecraft.getInstance().getWindow();
        if (from == null) {
            from = new org.joml.Vector2f(window.getGuiScaledWidth() / 2.0F, window.getGuiScaledHeight() / 2.0F);
        }
        gains.watch(player.getInventory(), carriedStack(), dt, station.scene().definition().gains(), from);
    }

    /** The cursor resting at the edge a neighbouring station stands on goes over to it; true when it did. */
    private boolean updateNeighbors(ClientStation station, float dt) {
        var window = Minecraft.getInstance().getWindow();
        float u = (float) (mouseX / window.getGuiScaledWidth());
        float v = (float) (mouseY / window.getGuiScaledHeight());
        Neighbors.Neighbor next = neighbors.watch(station, u, v, dt, mayGoOver());
        return next != null && DiegesisClientRuntime.goOver(next.pos(), next.rules().time());
    }

    /**
     * A movement or arrow key towards {@code edge}: goes over at once to the neighbour standing there, as the cursor resting at that
     * edge does; true when it did. Where the cursor is does not matter to a key.
     */
    boolean goOverByKey(InventoryDrawer.Side edge) {
        // Flying over, the press goes on from the station flown to.
        FocusController focus = FocusController.get();
        ClientStation station = focus.isInteractive() || focus.isGoingOver() ? focus.station() : null;
        Neighbors.Neighbor next = station == null || station.scene() == null ? null : neighbors.towards(station, edge);
        if (next == null || !next.rules().keys() || isBusy() || FocusScreen.isCovered() || !carriedStack().isEmpty()
                || glance.current() != null || glance.amount() > 0.0F) {
            return false;
        }
        return DiegesisClientRuntime.goOver(next.pos(), next.rules().time());
    }

    /** Nothing keeps the player at this station: an empty hand, no drag, nothing over the cursor, the look on the rig. */
    boolean mayGoOver() {
        return !isBusy() && hoveredInventory == -1 && !FocusScreen.isCovered() && carriedStack().isEmpty() && glance.current() == null
                && glance.amount() <= 0.0F;
    }

    /** The carried stack follows the cursor over the surface, leans as it moves and sticks to the slot under it. */
    private void updateCarry(ClientStation station, float dt, boolean carrying) {
        boolean overWorld = hoveredInventory == -1 && FocusScreen.isOverWorld(mouseX, mouseY);
        placer.update(station, stationRay(station, mouseX, mouseY), carriedStack(), overWorld, gesture instanceof Turn);
        if (!carrying) {
            carryPlaced = false;
            carryInWorld = false;
            return;
        }
        ItemPose preview = placer.preview();
        float plane = station.scene().definition().workPlane();
        InterfaceOptions options = station.scene().definition().ui();
        boolean overSlot = options.carryStick() && hovered != null && hovered.definition().slot().isPresent();
        boolean overInventory = options.carryStick() && sceneInventory >= 0;
        Vector3f onSurface = onPlane(stationRay(station, mouseX, mouseY), plane + options.carryLift(), options);
        // Over a slot it settles just off the slot's surface - above a table top, in front of an upright face.
        if (preview != null) {
            carryTarget.set(preview.x(), preview.y() + preview.lift(), preview.z());
            carry.setHalfLife(0.02F);
        } else if (overSlot) {
            carryTarget.set(hovered.matrix().transformPosition(new Vector3f(0.0F, STICK_HEIGHT, 0.0F)));
            carrySize = hovered.definition().slot().get().shownItemSize();
            carry.setHalfLife(0.045F);
        } else if (overInventory) {
            carryTarget.set(station.scene().inventorySlot(sceneInventory).transformPosition(new Vector3f(0.0F, STICK_HEIGHT, 0.0F)));
            carrySize = station.scene().inventoryLayout().size();
            carry.setHalfLife(0.045F);
        } else {
            if (onSurface != null) {
                carryTarget.set(onSurface);
            }
            carry.setHalfLife(0.02F);
            if (hovered != null && hovered.definition().dropZone().isPresent()) {
                carrySize = zoneItemSize(station, hovered.definition().dropZone().get());
            }
        }
        // With free placing the surfaces are the only places: anywhere else the stack is just on the cursor, as an icon.
        boolean inAir = onSurface != null && Placer.options(station) == null
                && onSurface.distance(stationRay(station, mouseX, mouseY).origin) >= NEAREST_CARRY;
        carryInWorld = overWorld && (preview != null || overSlot || overInventory || sceneInventory >= 0 || inAir);
        if (!carryPlaced) {
            carry.snap(carryTarget);
            carryPlaced = true;
        }
        carry.update(carryTarget, dt);
        Vector3fc velocity = carry.velocity();
        // Leans into the movement like something held between two fingers, and keeps a slow sway.
        float time = Util.getMillis() / 1000.0F;
        float sway = options.carrySway();
        float leanX = sway * (Mth.clamp(-velocity.z() * 0.9F, -28.0F, 28.0F) + (float) Math.sin(time * 2.1F) * 2.0F);
        float leanZ = sway * (Mth.clamp(velocity.x() * 0.9F, -28.0F, 28.0F) + (float) Math.sin(time * 1.7F + 1.0F) * 2.0F);
        lean.set(Easing.approach(lean.x, leanX, 0.06F, dt), 0.0F, Easing.approach(lean.z, leanZ, 0.06F, dt));
    }

    /** How big a stack is over a drop zone: the size of the things that lie in it. */
    private float zoneItemSize(ClientStation station, String group) {
        for (NodeInstance node : station.scene().nodes()) {
            SlotBinding slot = node.definition().slot().orElse(null);
            if (slot != null && slot.slot().group().equals(group)) {
                return slot.shownItemSize();
            }
        }
        return carrySize;
    }

    /** Starts the carried pose at the slot it was taken from, so it lifts off from there. */
    private void snapCarryTo(ClientStation station, Target target, NodeInstance node) {
        if (node != null) {
            Vector3f at = node.matrix().transformPosition(new Vector3f());
            carry.snap(at);
            carryPlaced = true;
            node.definition().slot().ifPresent(slot -> carrySize = slot.shownItemSize());
        } else if (target instanceof Target.Inventory inventory && station.scene().inventoryLayout() != null) {
            carry.snap(station.scene().inventorySlot(inventory.index()).transformPosition(new Vector3f()));
            carryPlaced = true;
            carrySize = station.scene().inventoryLayout().size();
        }
    }

    /** What the cursor ray meets first: a node passing {@code filter}, or an inventory slot laid out in the scene. */
    private Picked pickAll(ClientStation station, Predicate<NodeInstance> filter) {
        StationRay ray = stationRay(station, mouseX, mouseY);
        SceneInstance.Hit hit = station.scene().pick(ray.origin, ray.direction, filter);
        SceneInstance.InventoryHit slot = isInventoryShown() ? station.scene().pickInventory(ray.origin, ray.direction) : null;
        if (slot != null && (hit == null || slot.distance() < hit.distance())) {
            return new Picked(null, slot.slot());
        }
        return new Picked(hit == null ? null : hit.node(), -1);
    }

    private record Picked(NodeInstance node, int inventorySlot) {
        static final Picked NONE = new Picked(null, -1);
    }

    /**
     * Whether the cursor stops at a node (hovers it, clicks it): anything the player can use, a node with a hit box of its
     * own (clicked for {@code SceneEvent.Click}) and a node with a tooltip to read. Trigger zones and the needles of
     * locked controls (driven by the logic, not the player) let the cursor through, unless they have a tooltip.
     */
    private boolean isClickable(NodeInstance node) {
        NodeDefinition definition = node.definition();
        if (definition.trigger() || !node.isEnabled()) {
            return false;
        }
        if (definition.dropZone().isPresent()) {
            return zoneSlot(station(), definition.dropZone().get(), carriedStack()) != null;
        }
        if (definition.tooltip().isPresent() || node.tooltipOverride() != null) {
            return true;
        }
        if (definition.control().isPresent()) {
            return !isLockedControl(station(), definition);
        }
        return definition.isInteractive() || definition.collider().isPresent();
    }

    /** Whether a node drives a control the player cannot operate (a gauge's needle bound to a locked control). */
    public static boolean isLockedControl(ClientStation station, NodeDefinition definition) {
        if (station == null || definition.control().isEmpty()) {
            return false;
        }
        ControlSpec spec = station.type().schema().controls().get(definition.control().get().control());
        return spec == null || spec.isLocked();
    }

    /** Whether a node is a slot of the scene file; a placed item is no place to put a stack into. */
    private static boolean isFixedSlot(NodeInstance node) {
        return node.definition().slot().isPresent() && node.placed() == null;
    }

    /** The slot of a group a stack goes to: one it merges with, else the first empty one; null when it does not fit. */
    private static SlotRef zoneSlot(ClientStation station, String group, ItemStack stack) {
        StationSchema.SlotGroup slots = station == null ? null : station.type().schema().slotGroups().get(group);
        if (slots == null || stack.isEmpty() || !slots.rules().accepts().test(stack)) {
            return null;
        }
        SlotRef empty = null;
        for (int i = 0; i < slots.size(); i++) {
            SlotRef ref = slots.ref(i);
            ItemStack there = station.state().getItem(ref);
            if (there.isEmpty()) {
                empty = empty == null ? ref : empty;
            } else if (ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < slots.rules().limitFor(there)) {
                return ref;
            }
        }
        return empty;
    }

    private static Target itemTarget(ClientStation station, NodeDefinition definition) {
        if (definition.dropZone().isPresent()) {
            SlotRef slot = zoneSlot(station, definition.dropZone().get(), carriedStack());
            return slot == null ? null : new Target.Station(slot);
        }
        if (definition.slot().isPresent()) {
            return new Target.Station(definition.slot().get().slot());
        }
        if (definition.output().isPresent()) {
            return new Target.Output(definition.output().get().output());
        }
        return null;
    }

    private static ItemStack itemAt(ClientStation station, Target target) {
        if (target instanceof Target.Station slot) {
            return station.state().getItem(slot.slot());
        }
        if (target instanceof Target.Output output) {
            return station.state().getOutput(output.output());
        }
        if (target instanceof Target.Inventory inventory && Minecraft.getInstance().player != null) {
            return Minecraft.getInstance().player.getInventory().getItem(inventory.index());
        }
        return ItemStack.EMPTY;
    }

    private void setHovered(NodeInstance node) {
        if (node == hovered) {
            return;
        }
        if (hovered != null) {
            hovered.setHovered(false);
        }
        hovered = node;
        if (node != null) {
            node.setHovered(true);
        }
        ClientStation station = station();
        if (station != null) {
            station.onEvent(new SceneEvent.Hover(node == null ? null : node.id()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------------------------------------------

    private void startControl(ClientStation station, NodeInstance node, ControlBinding control, double x, double y) {
        ControlSpec spec = station.type().schema().controls().get(control.control());
        if (spec == null || spec.isLocked()) {
            return;
        }
        switch (control.type()) {
            case BUTTON -> {
                node.pulse();
                UiSounds.button(node.worldPosition());
                if (spec.isButton()) {
                    station.perform(new StationAction.Press(control.control()));
                }
                station.onEvent(new SceneEvent.ButtonPressed(control.control()));
            }
            case TOGGLE -> {
                if (!spec.isButton()) {
                    float current = station.state().getControl(control.control());
                    float next = spec.fraction(current) < 0.5F ? spec.max() : spec.min();
                    setControl(station, node, control, spec, next, true);
                    UiSounds.toggle(node.worldPosition(), next > current);
                }
            }
            case KNOB, SLIDER -> {
                if (!spec.isButton()) {
                    gesture = new ControlDrag(station, node, control, spec, x, y);
                }
            }
        }
    }

    private void setControl(ClientStation station, NodeInstance node, ControlBinding control, ControlSpec spec, float value, boolean send) {
        float before = station.state().getControl(control.control());
        float sanitized = spec.sanitize(value);
        if (sanitized == before) {
            return;
        }
        if (send) {
            station.perform(new StationAction.SetControl(control.control(), sanitized));
        } else {
            station.state().setControl(control.control(), sanitized);
        }
        if (spec.steps() > 0 && control.type() == ControlBinding.Type.KNOB) {
            UiSounds.detent(node.worldPosition(), spec.fraction(sanitized));
        }
        station.onEvent(new SceneEvent.ControlMoved(control.control(), sanitized));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private static ClientStation station() {
        FocusController focus = FocusController.get();
        return focus.isInteractive() ? focus.station() : null;
    }

    private SceneInstance.Hit pick(ClientStation station, Predicate<NodeInstance> filter) {
        StationRay ray = stationRay(station, mouseX, mouseY);
        return station.scene().pick(ray.origin, ray.direction, filter);
    }

    /** The cursor ray in the station's space. */
    static StationRay stationRay(ClientStation station, double x, double y) {
        Projector.Ray ray = Projector.rayAt(x, y);
        Matrix4f inverse = station.stationToWorld(new Matrix4f()).invert();
        Vec3 origin = ray.origin().subtract(station.pos().getX(), station.pos().getY(), station.pos().getZ());
        Vector3f localOrigin = inverse.transformPosition(new Vector3f((float) origin.x, (float) origin.y, (float) origin.z));
        Vector3f localDirection = inverse.transformDirection(new Vector3f(ray.direction()));
        return new StationRay(localOrigin, localDirection);
    }

    /**
     * Where the cursor ray meets the horizontal plane at {@code height} pixels, kept within the scene's carry margin;
     * null if never.
     */
    static Vector3f onPlane(StationRay ray, float height, InterfaceOptions options) {
        if (Math.abs(ray.direction.y) < 1.0e-5F) {
            return null;
        }
        float t = (height - ray.origin.y) / ray.direction.y;
        if (t < 0.0F) {
            return null;
        }
        Vector3f point = new Vector3f(ray.direction).mul(t).add(ray.origin);
        float margin = options.carryMargin().orElse(FAR_LIMIT);
        point.x = Mth.clamp(point.x, -margin, 16.0F + margin);
        point.z = Mth.clamp(point.z, -margin, 16.0F + margin);
        return point;
    }

    /**
     * Where the cursor ray meets the surface a grabbable node is carried over: the work plane plus its lift, raised by
     * the scene's carry planes. The ray is walked down from the camera until it drops below the surface, then the
     * crossing is narrowed down; without a crossing it falls back to the flat plane.
     */
    static Vector3f onCarrySurface(StationRay ray, SceneDefinition scene, String node, float lift) {
        Vector3f point = surfacePoint(ray, scene, node, lift);
        if (point != null) {
            for (CarryPlane plane : scene.carryPlanes()) {
                if (plane.appliesTo(node)) {
                    plane.keepOut(point);
                }
            }
        }
        return point;
    }

    private static Vector3f surfacePoint(StationRay ray, SceneDefinition scene, String node, float lift) {
        float base = scene.workPlane() + lift;
        if (scene.carryPlanes().stream().noneMatch(plane -> plane.height().isPresent())) {
            return onPlane(ray, base, scene.ui());
        }
        Vector3f point = new Vector3f();
        float step = 0.5F;
        float previous = 0.0F;
        for (float t = 0.0F; t < 512.0F; t += step) {
            ray.direction.mul(t, point).add(ray.origin);
            if (point.y <= scene.carryHeight(node, lift, point.x, point.z)) {
                float low = previous;
                float high = t;
                for (int i = 0; i < 14; i++) {
                    float mid = (low + high) / 2.0F;
                    ray.direction.mul(mid, point).add(ray.origin);
                    if (point.y <= scene.carryHeight(node, lift, point.x, point.z)) {
                        high = mid;
                    } else {
                        low = mid;
                    }
                }
                ray.direction.mul(high, point).add(ray.origin);
                float margin = scene.ui().carryMargin().orElse(FAR_LIMIT);
                point.x = Mth.clamp(point.x, -margin, 16.0F + margin);
                point.z = Mth.clamp(point.z, -margin, 16.0F + margin);
                return point;
            }
            if (point.y < base - 64.0F) {
                break;
            }
            previous = t;
        }
        return onPlane(ray, base, scene.ui());
    }

    private void sendPresence(Presence presence, boolean force) {
        ClientStation station = FocusController.get().station();
        if (station == null || station.type().authority() != Authority.SERVER || !ClientPlatform.INSTANCE.serverHasChannel()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastPresence < PRESENCE_INTERVAL_MS) {
            return;
        }
        lastPresence = now;
        ClientPlatform.INSTANCE.sendToServer(new Packets.PresenceUpdate(station.pos(), presence));
    }

    /** Tells the players around where the carried stack is (the server fills in the item from the carried stack). */
    private void reportCarry(ClientStation station) {
        if (gesture != null) {
            return;
        }
        if (carriedStack().isEmpty() || !carryInWorld) {
            if (lastReportedCarry) {
                sendPresence(Presence.NONE, true);
                lastReportedCarry = false;
            }
            return;
        }
        lastReportedCarry = true;
        sendPresence(Presence.holding("", Presence.CARRIED_SLOT, ItemStack.EMPTY, new Vector3f(carry.value()), new Vector3f(lean)), false);
    }

    record StationRay(Vector3f origin, Vector3f direction) {
    }

    /** How full a tank is, 0 to 1, and the RGB colour of what is in it. */
    record Meter(float fill, int color) {
    }

    /** The carried stack, for drawing: in 3D over the station (pose in station space) or as an icon at the cursor. */
    public record Carried(ItemStack stack, Vector3fc stationPos, Vector3fc lean, boolean inWorld, float size, double mouseX,
            double mouseY, ItemPose preview) {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gestures
    // ---------------------------------------------------------------------------------------------------------------

    private interface Gesture {
        void drag(double x, double y);

        void frame();

        void release();

        void cancel();

        /** What others should see, or null for nothing to report. */
        Presence presence();
    }

    /** A button held on a node that takes strokes: the behaviour hears where the cursor is, every frame, until it is let go. */
    private final class Stroke implements Gesture {
        private final ClientStation station;
        private final NodeInstance node;
        private final int button;
        /** Kept going after its button came up ({@link #holdStroke}): the next press ends it and does nothing else. */
        private final boolean held;

        Stroke(ClientStation station, NodeInstance node, int button, boolean held) {
            this.station = station;
            this.node = node;
            this.button = button;
            this.held = held;
            report(SceneEvent.Stroke.Phase.START);
        }

        private void report(SceneEvent.Stroke.Phase phase) {
            station.onEvent(new SceneEvent.Stroke(node.id(), phase, button, strokePoint(station, node)));
        }

        @Override
        public void drag(double x, double y) {
        }

        @Override
        public void frame() {
            report(SceneEvent.Stroke.Phase.MOVE);
        }

        @Override
        public void release() {
            // While the behaviour hears this END it may keep the stroke going.
            ending = this;
            try {
                report(SceneEvent.Stroke.Phase.END);
            } finally {
                ending = null;
            }
        }

        @Override
        public void cancel() {
            report(SceneEvent.Stroke.Phase.END);
        }

        @Override
        public Presence presence() {
            return null;
        }
    }

    /**
     * The behaviour heard a stroke end and keeps it going: a fresh stroke on the same node, reported from START, which the next press
     * ends instead of doing anything else. Only while that END is heard, and only when nothing else has started meanwhile.
     */
    public void holdStroke(ClientStation station) {
        if (ending != null && ending.station == station && gesture == null) {
            gesture = new Stroke(station, ending.node, ending.button, true);
        }
    }

    /** The right button held: the drag turns the item under it, or the stack in the hand, which stays where it is meanwhile. */
    private final class Turn implements Gesture {
        private final ClientStation station;
        private final NodeInstance node;
        private double lastX;
        private double lastY;
        private long lastSent;

        Turn(ClientStation station, NodeInstance node, double x, double y) {
            this.station = station;
            this.node = node;
            lastX = x;
            lastY = y;
        }

        @Override
        public void drag(double x, double y) {
            float dx = (float) (x - lastX);
            float dy = (float) (y - lastY);
            lastX = x;
            lastY = y;
            Vector3f right = screenRight(station);
            if (node == null) {
                placer.dragCarried(Placer.options(station), dx, dy, right);
                return;
            }
            long now = Util.getMillis();
            boolean send = now - lastSent >= CONTROL_SEND_INTERVAL_MS;
            if (send) {
                lastSent = now;
            }
            placer.dragPlaced(station, node, dx, dy, right, send);
        }

        @Override
        public void frame() {
        }

        @Override
        public void release() {
            if (node != null) {
                placer.commit(station, node);
            }
        }

        @Override
        public void cancel() {
            release();
        }

        @Override
        public Presence presence() {
            return null;
        }
    }

    /**
     * A grabbable node being carried. A vessel with a spout pours when it is pointed at another tank that takes its
     * liquid: the cursor anywhere on the tank or in the column over its opening is enough. The vessel then glides until
     * its spout is over the opening and tips as far as the liquid needs to reach the spout, so a full vessel pours early
     * and an emptying one goes on tipping; how far the liquid stands over the spout is the flow, and the mouse wheel
     * sets how much the hand asks for. Nothing happens from afar: the zone is the tank itself plus {@code pour_range}.
     */
    private final class NodeGrab implements Gesture {
        /** Seconds between two pours sent to the logic. */
        private static final float POUR_STEP = 0.1F;
        /** How far (pixels) above the opening the zone reaches, besides the spout's own height over it. */
        private static final float ZONE_HEADROOM = 3.0F;
        /** Once pointed at, a tank holds the vessel in a zone this much wider: no flicker at its edge. */
        private static final float ZONE_STICKY = 1.35F;
        /** Degrees per second the hand tips at most, forwards and back. */
        private static final float TIP_SPEED = 170.0F;
        private static final float RIGHT_SPEED = 240.0F;
        /** How hard the hand answers a difference in the liquid's height at the spout. */
        private static final float TIP_RESPONSE = 70.0F;
        /** The flow the hand asks for at first, and what one notch of the wheel changes. */
        private static final float FLOW_DEFAULT = 0.7F;
        private static final float FLOW_NOTCH = 0.15F;
        private static final float FLOW_MIN = 0.2F;
        /** The flow at the deepest tilt, when the liquid left can no longer reach a high spout by itself. */
        private static final float FLOW_AT_LIMIT = 0.45F;

        private final ClientStation station;
        private final NodeInstance node;
        private final Grabbable grabbable;
        /** The node (the grabbed one or a part of it) whose tank pours out through its spout; null when none. */
        private final NodeInstance source;
        private final long pressedAt = Util.getMillis();
        private final double pressX;
        private final double pressY;
        private boolean kept;

        private final List<NodeInstance> candidates = new ArrayList<>();
        /** The tank being poured into, and the way the vessel tips for it (fixed while the target lasts). */
        private NodeInstance pourTarget;
        private final Vector3f towards = new Vector3f(1.0F, 0.0F, 0.0F);
        /** How far the vessel has gone from the hand to the pouring place, 0 to 1. */
        private float settled;
        /** Where the pouring place puts the node's origin. */
        private final Vector3f pourPlace = new Vector3f();
        private float tilt;
        private float tipSpeed;
        private float turn;
        private float flowAsked = FLOW_DEFAULT;
        private float flow;
        private boolean running;
        private float pourClock;
        private float pourDebt;
        private float soundClock;

        /** The point it is held by, in the node's pixels. */
        private final Vector3f grip;

        NodeGrab(ClientStation station, NodeInstance node, Grabbable grabbable, double x, double y) {
            this.station = station;
            this.node = node;
            this.grabbable = grabbable;
            this.source = node.pourer();
            this.pressX = x;
            this.pressY = y;
            Box box = node.collider();
            this.grip = grabbable.grip().map(Vector3f::new).orElseGet(() -> box == null ? new Vector3f()
                    : new Vector3f(box.from()).add(box.to()).mul(0.5F));
            node.grab(heldPlace());
        }

        /** Whether it stays in the hand after the button was let go, until the next click. */
        boolean isKept() {
            return kept;
        }

        /** The button came up: keeps the object in the hand if this was a click and the object works that way. */
        boolean keepAfterRelease(double x, double y) {
            boolean click = Util.getMillis() - pressedAt < QUICK_CLICK_MS && Math.hypot(x - pressX, y - pressY) < CLICK_TRAVEL;
            kept = switch (grabbable.hold()) {
                case AUTO -> click;
                case CLICK -> true;
                case DRAG -> false;
            };
            return kept;
        }

        /** The wheel: more or less flow. */
        void scroll(double amount) {
            flowAsked = Mth.clamp(flowAsked + (float) Math.signum(amount) * FLOW_NOTCH, FLOW_MIN, 1.0F);
        }

        Meter meter() {
            if (pourTarget == null || !station.scene().definition().pouring().meter()) {
                return null;
            }
            TankBinding to = pourTarget.definition().tank().get();
            TankSpec spec = station.type().schema().tanks().get(to.tank());
            if (spec == null || spec.capacity() <= 0) {
                return null;
            }
            float fill = Math.min(1.0F, (float) station.state().getTank(to.tank()).amount() / spec.capacity());
            return new Meter(fill, source.liquid().color() & 0xFFFFFF);
        }

        private boolean isPartOfHeld(NodeInstance other) {
            for (NodeInstance at = other; at != null; at = at.parent()) {
                if (at == node) {
                    return true;
                }
            }
            return false;
        }

        /** Finds the tank pointed at, moves the vessel to it and lets the liquid run. */
        private void pour(float dt) {
            if (source == null) {
                return;
            }
            TankBinding from = source.definition().tank().get();
            NodeInstance target = findTarget(from);
            if (target != pourTarget) {
                stopRunning();
                pourTarget = target;
                if (target != null) {
                    chooseDirection(target);
                }
            }
            settled = Easing.approach(settled, target != null ? 1.0F : 0.0F, 0.07F, dt);
            if (target == null) {
                flow = 0.0F;
                tilt = Math.max(0.0F, tilt - RIGHT_SPEED * dt);
                tipSpeed = 0.0F;
                turn = Easing.approach(turn, 0.0F, 0.08F, dt);
                node.setTilt(tiltAxis(), tilt, turn);
                return;
            }
            float head = liquidOverSpout(from);
            float scale = headScale(from);
            placeSpout(target, from, head);
            turn = spoutTurn(from);
            if (isDone(target, from)) {
                // Nothing left to pour or no room left: it rights itself and waits by the tank.
                flow = 0.0F;
                tipSpeed = 0.0F;
                tilt = Math.max(0.0F, tilt - RIGHT_SPEED * dt);
                node.setTilt(tiltAxis(), tilt, turn);
                run(target, from, dt);
                return;
            }
            if (settled > 0.4F) {
                float wanted = Mth.clamp((scale * flowAsked - head) * TIP_RESPONSE, -TIP_SPEED, TIP_SPEED);
                tipSpeed = Easing.approach(tipSpeed, wanted, 0.05F, dt);
                tilt = Mth.clamp(tilt + tipSpeed * dt, 0.0F, from.pourAngle());
            }
            node.setTilt(tiltAxis(), tilt, turn);
            flow = head > 0.0F ? Math.min(1.0F, head / scale) : 0.0F;
            if (tilt >= from.pourAngle() - 1.0F) {
                flow = Math.max(flow, FLOW_AT_LIMIT * flowAsked);
            }
            if (settled < 0.6F) {
                flow = 0.0F;
            }
            run(target, from, dt);
        }

        private boolean isDone(NodeInstance target, TankBinding from) {
            TankBinding to = target.definition().tank().get();
            TankSpec spec = station.type().schema().tanks().get(to.tank());
            return station.state().getTank(from.tank()).isEmpty() || spec == null
                    || station.state().getTank(to.tank()).amount() >= spec.capacity();
        }

        private void run(NodeInstance target, TankBinding from, float dt) {
            boolean now = flow > 0.0F;
            if (now != running) {
                running = now;
                station.onEvent(new SceneEvent.Pour(source.id(), target.id(), now));
            }
            if (!now) {
                return;
            }
            station.pourStreams().feed(source, target, flow);
            TankBinding to = target.definition().tank().get();
            TankSpec fromSpec = station.type().schema().tanks().get(from.tank());
            TankSpec toSpec = station.type().schema().tanks().get(to.tank());
            if (fromSpec == null || toSpec == null) {
                return;
            }
            pourDebt += Math.min(fromSpec.pourRate(), toSpec.pourRate()) * flow * dt;
            pourClock += dt;
            if (pourClock >= POUR_STEP) {
                pourClock -= POUR_STEP;
                int amount = (int) pourDebt;
                if (amount > 0) {
                    pourDebt -= amount;
                    station.perform(new StationAction.Pour(from.tank(), to.tank(), amount));
                }
            }
            soundClock -= dt;
            if (soundClock <= 0.0F) {
                soundClock = 0.45F;
                UiSounds.pour(target.worldPosition(), flow, target.liquid().fraction());
            }
        }

        private void stopRunning() {
            if (running && pourTarget != null) {
                station.onEvent(new SceneEvent.Pour(source.id(), pourTarget.id(), false));
            }
            running = false;
            pourDebt = 0.0F;
        }

        /** How far the liquid stands over the spout, in the vessel's pixels; below 0 it does not reach it yet. */
        private float liquidOverSpout(TankBinding from) {
            Box inner = from.inner();
            TankSpec spec = station.type().schema().tanks().get(from.tank());
            if (spec == null || spec.capacity() <= 0) {
                return -1.0F;
            }
            float cx = (inner.from().x() + inner.to().x()) / 2.0F;
            float cy = (inner.from().y() + inner.to().y()) / 2.0F;
            float cz = (inner.from().z() + inner.to().z()) / 2.0F;
            Vector3f up = source.matrix().invert(new Matrix4f()).transformDirection(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
            float fill = Math.min(1.0F, (float) station.state().getTank(from.tank()).amount() / spec.capacity());
            float level = LiquidVolume.level((inner.to().x() - inner.from().x()) / 2.0F, (inner.to().y() - inner.from().y()) / 2.0F,
                    (inner.to().z() - inner.from().z()) / 2.0F, up.x, up.y, up.z, fill);
            Vector3fc spout = from.spout().get();
            return level - up.dot(spout.x() - cx, spout.y() - cy, spout.z() - cz);
        }

        /** The height of liquid over the spout that means full flow: more for a big vessel. */
        private float headScale(TankBinding from) {
            return Math.max(0.8F, (from.inner().to().y() - from.inner().from().y()) * 0.12F);
        }

        /**
         * The tank the cursor points at, among those that take the liquid: the ray must pass within a capsule round the
         * tank's upright axis, from its bottom to just over its opening, as wide as the tank plus the vessel's
         * {@code pour_range}. Measured in the scene, so perspective cannot inflate it. Also collects the tanks for the hints.
         */
        private NodeInstance findTarget(TankBinding from) {
            candidates.clear();
            StationRay ray = stationRay(station, mouseX, mouseY);
            Vector3f direction = new Vector3f(ray.direction).normalize();
            NodeInstance best = null;
            float bestShare = Float.MAX_VALUE;
            for (NodeInstance other : station.scene().nodes()) {
                if (other.definition().tank().isEmpty() || !other.isVisible() || isPartOfHeld(other)
                        || !accepts(other.definition().tank().get(), other == pourTarget)) {
                    continue;
                }
                candidates.add(other);
                TankBinding tank = other.definition().tank().get();
                float[] box = bounds(other, tank.inner());
                float x = (box[0] + box[3]) / 2.0F;
                float z = (box[2] + box[5]) / 2.0F;
                float top = Math.max(box[4], other.matrix().transformPosition(tank.mouth()).y) + from.pourHeight() + ZONE_HEADROOM;
                float radius = Math.max(box[3] - box[0], box[5] - box[2]) / 2.0F + from.pourRange();
                float distance = rayToUpright(ray.origin, direction, x, box[1], top, z);
                float share = distance / (radius * (other == pourTarget ? ZONE_STICKY : 1.0F));
                if (share <= 1.0F && share < bestShare) {
                    bestShare = share;
                    best = other;
                }
            }
            return best;
        }

        /** The shortest distance from a ray (unit direction) to the upright line at {@code x, z} from {@code low} to {@code high}. */
        private static float rayToUpright(Vector3fc origin, Vector3fc direction, float x, float low, float high, float z) {
            float length = high - low;
            float wx = origin.x() - x;
            float wy = origin.y() - low;
            float wz = origin.z() - z;
            float along = direction.y() * length;
            float d = direction.x() * wx + direction.y() * wy + direction.z() * wz;
            float e = length * wy;
            float denominator = length * length - along * along;
            float s = denominator > 1.0e-5F ? Mth.clamp((e - d * along) / denominator, 0.0F, 1.0F) : 0.0F;
            float t = Math.max(0.0F, along * s - d);
            float dx = wx + direction.x() * t;
            float dy = wy + direction.y() * t - length * s;
            float dz = wz + direction.z() * t;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /**
         * The vessel tips sideways as the player sees it - the stream is in plain view - with its top going towards the
         * tank and its body staying on the side it came from. Chosen once, when the tank is found.
         */
        private void chooseDirection(NodeInstance target) {
            Vector3f mouth = target.matrix().transformPosition(target.definition().tank().get().mouth());
            Vector3fc holder = node.carryPosition();
            Vector3f view = new Vector3f(mouth).sub(stationRay(station, mouseX, mouseY).origin);
            towards.set(-view.z, 0.0F, view.x);
            if (towards.lengthSquared() < 1.0e-6F) {
                towards.set(1.0F, 0.0F, 0.0F);
            }
            towards.normalize();
            if (towards.dot(mouth.x - holder.x(), 0.0F, mouth.z - holder.z()) < 0.0F) {
                towards.negate();
            }
        }

        /** Tipping about the horizontal line across the way to the tank brings the top over to it. */
        private Vector3f tiltAxis() {
            return new Vector3f(towards.z, 0.0F, -towards.x);
        }

        /**
         * Where the node's origin goes. Tipped far enough to pour, its spout is over the opening; still upright, it waits
         * beside the tank at that height, never inside it or in the table, and tipping brings the spout across. The hand
         * still moves it a little.
         */
        private void placeSpout(NodeInstance target, TankBinding from, float head) {
            TankBinding to = target.definition().tank().get();
            Vector3f goal = target.matrix().transformPosition(to.mouth()).add(0.0F, from.pourHeight(), 0.0F);
            Vector3f hand = heldPoint();
            if (hand != null) {
                float dx = Mth.clamp((hand.x - goal.x) * 0.06F, -0.6F, 0.6F);
                float dz = Mth.clamp((hand.z - goal.z) * 0.06F, -0.6F, 0.6F);
                goal.add(dx, 0.0F, dz);
            }
            Vector3f origin = node.matrix().getTranslation(new Vector3f());
            Vector3f spoutFromOrigin = PourStreams.spout(source).sub(origin);
            float[] own = bounds(source, from.inner());
            float[] theirs = bounds(target, to.inner());
            float clearance = (Math.max(own[3] - own[0], own[5] - own[2]) + Math.max(theirs[3] - theirs[0], theirs[5] - theirs[2])) / 2.0F + 0.6F;
            float height = from.inner().to().y() - from.inner().from().y();
            float away = Mth.clamp(-head / (height * 0.7F), 0.0F, 1.0F);
            goal.fma(-clearance * away, towards);
            goal.y = Math.max(goal.y, station.scene().definition().workPlane() + spoutFromOrigin.y + 0.5F);
            pourPlace.set(goal).sub(spoutFromOrigin);
        }

        /** The turn about the vessel's own axis that brings a spout on its rim round to the tipping side. */
        private float spoutTurn(TankBinding from) {
            Box inner = from.inner();
            Vector3fc spout = from.spout().get();
            float sx = spout.x() - (inner.from().x() + inner.to().x()) / 2.0F;
            float sz = spout.z() - (inner.from().z() + inner.to().z()) / 2.0F;
            if (Math.hypot(sx, sz) < 0.5F) {
                return 0.0F;
            }
            Quaternionf own = Transform.toQuaternion(node.definition().transform().rotation(), new Quaternionf()).invert();
            Vector3f wanted = own.transform(new Vector3f(towards));
            return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(wanted.x, wanted.z) - Math.atan2(sx, sz)));
        }

        /** Where the cursor holds the grip: on the object's plane or the carry surface; null when the ray misses it. */
        private Vector3f heldPoint() {
            SceneDefinition scene = station.scene().definition();
            StationRay ray = stationRay(station, mouseX, mouseY);
            return grabbable.plane().isPresent() ? grabbable.plane().get().intersect(ray.origin, ray.direction)
                    : onCarrySurface(ray, scene, node.id(), grabbable.lift());
        }

        /** Where the hand alone puts the node's origin. */
        private Vector3f heldPlace() {
            Vector3f point = heldPoint();
            if (point == null) {
                return node.homePosition();
            }
            return point.sub(node.matrix().transformDirection(new Vector3f(grip)));
        }

        /**
         * Whether a tank would take some of what the held vessel pours: no tipping towards one that refuses it. The tank
         * being poured into keeps the vessel when it fills up or the vessel runs dry, so it rights itself in place.
         */
        private boolean accepts(TankBinding to, boolean current) {
            TankBinding from = source.definition().tank().get();
            TankSpec toSpec = station.type().schema().tanks().get(to.tank());
            TankContents contents = station.state().getTank(from.tank());
            if (toSpec == null || to.tank().equals(from.tank())) {
                return false;
            }
            if (!current && (contents.isEmpty() || station.state().getTank(to.tank()).amount() >= toSpec.capacity())) {
                return false;
            }
            return contents.portions().stream().allMatch(portion -> toSpec.accepts().test(portion.liquid()));
        }

        @Override
        public void drag(double x, double y) {
        }

        @Override
        public void frame() {
            pour(frameDt);
            Vector3f place = heldPlace();
            node.carryTo(settled > 0.001F ? place.lerp(pourPlace, settled) : place);
            contacts();
        }

        private final java.util.Set<NodeInstance> touching = new java.util.HashSet<>();
        private final Vector3f lastAt = new Vector3f(Float.NaN, 0.0F, 0.0F);

        /** Reports which nodes the carried one starts or stops touching (hit boxes in station space). */
        private void contacts() {
            Box touchingPart = grabbable.contact().orElse(node.collider());
            if (touchingPart == null) {
                return;
            }
            Vector3f at = new Vector3f(node.carryPosition());
            float speed = Float.isNaN(lastAt.x) || frameDt <= 0.0F ? 0.0F : at.distance(lastAt) / frameDt;
            lastAt.set(at);
            float[] own = bounds(node, touchingPart);
            for (NodeInstance other : station.scene().nodes()) {
                if (other.collider() == null || !other.isVisible() || isPartOfHeld(other)) {
                    continue;
                }
                float[] box = bounds(other, other.collider());
                boolean overlaps = own[0] < box[3] && own[3] > box[0] && own[1] < box[4] && own[4] > box[1] && own[2] < box[5]
                        && own[5] > box[2];
                if (overlaps && touching.add(other)) {
                    station.onEvent(new SceneEvent.Contact(node.id(), other.id(), true, speed));
                } else if (!overlaps && touching.remove(other)) {
                    station.onEvent(new SceneEvent.Contact(node.id(), other.id(), false, speed));
                }
            }
        }

        /** A box of a node (in its pixels) as an axis-aligned box in station space: min x, y, z, max x, y, z. */
        private static float[] bounds(NodeInstance node, Box part) {
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            Vector3fc from = part.from();
            Vector3fc to = part.to();
            for (int corner = 0; corner < 8; corner++) {
                Vector3f point = node.matrix().transformPosition(new Vector3f((corner & 1) == 0 ? from.x() : to.x(),
                        (corner & 2) == 0 ? from.y() : to.y(), (corner & 4) == 0 ? from.z() : to.z()));
                box[0] = Math.min(box[0], point.x);
                box[1] = Math.min(box[1], point.y);
                box[2] = Math.min(box[2], point.z);
                box[3] = Math.max(box[3], point.x);
                box[4] = Math.max(box[4], point.y);
                box[5] = Math.max(box[5], point.z);
            }
            return box;
        }

        @Override
        public void release() {
            String target = hovered != null && hovered != node ? hovered.id() : null;
            letGo();
            node.release(grabbable.stay());
            UiSounds.setDown(node.worldPosition());
            station.onEvent(new SceneEvent.Release(node.id(), target));
        }

        @Override
        public void cancel() {
            letGo();
            node.release(false);
        }

        private void letGo() {
            stopRunning();
            pourTarget = null;
            candidates.clear();
            node.setTilt(tiltAxis(), 0.0F, 0.0F);
        }

        @Override
        public Presence presence() {
            Vector3f tipped = tiltAxis().mul(tilt);
            return new Presence(node.id(), -1, ItemStack.EMPTY, new Vector3f(node.carryPosition()), new Vector3f(node.lean()), tipped, turn,
                    pourTarget == null || flow <= 0.0F ? "" : pourTarget.id(), flow);
        }
    }

    /** A knob turned or a slider pulled by dragging. */
    private final class ControlDrag implements Gesture {
        private final ClientStation station;
        private final NodeInstance node;
        private final ControlBinding control;
        private final ControlSpec spec;
        private final float startFraction;
        private final double startX;
        private final double startY;
        private final Vector2f screenAxis;
        /** For a knob turned round: its centre on the screen; null when it is dragged. */
        private Vector2f circular;
        private float lastAngle;
        private float handedness = 1.0F;
        /** Degrees turned since the grab. */
        private float turned;
        private long lastSent;
        private float sent;

        ControlDrag(ClientStation station, NodeInstance node, ControlBinding control, ControlSpec spec, double x, double y) {
            this.station = station;
            this.node = node;
            this.control = control;
            this.spec = spec;
            this.startFraction = spec.fraction(station.state().getControl(control.control()));
            this.startX = x;
            this.startY = y;
            this.screenAxis = control.type() == ControlBinding.Type.SLIDER ? sliderAxis() : null;
            this.sent = station.state().getControl(control.control());
            this.circular = control.type() == ControlBinding.Type.KNOB && control.circular() ? knobCentre() : null;
            if (circular != null) {
                lastAngle = (float) Math.atan2(y - circular.y, x - circular.x);
                handedness = handedness();
            }
        }

        /** The knob's centre on the screen (GUI pixels): the middle of its hit box. */
        private Vector2f knobCentre() {
            Box box = node.collider();
            Vector3f middle = box == null ? new Vector3f() : new Vector3f(box.from()).add(box.to()).mul(0.5F);
            Vector3f at = node.matrix().transformPosition(middle);
            return Projector.toScreen(station.toWorld(at.x, at.y, at.z));
        }

        /**
         * +1 when the knob's turning axis points at the camera (a turn about it looks counter-clockwise on the screen),
         * -1 when it points away.
         */
        private float handedness() {
            Vector3f axis = node.restMatrix().transformDirection(new Vector3f(control.axis()));
            Vector3f at = node.matrix().transformPosition(new Vector3f());
            Vector3f toCamera = new Vector3f(stationRay(station, startX, startY).origin).sub(at);
            return axis.dot(toCamera) >= 0.0F ? 1.0F : -1.0F;
        }

        /** The slider's full travel as a GUI vector. */
        private Vector2f sliderAxis() {
            Matrix4f rest = node.restMatrix();
            Vector3f origin = rest.transformPosition(new Vector3f());
            Vector3f end = rest.transformPosition(new Vector3f(control.axis()).mul(control.range()[1] - control.range()[0]));
            Vector2f a = Projector.toScreen(station.toWorld(origin.x, origin.y, origin.z));
            Vector2f b = Projector.toScreen(station.toWorld(end.x, end.y, end.z));
            if (a == null || b == null || a.distanceSquared(b) < 1.0F) {
                return new Vector2f(80.0F, 0.0F);
            }
            return b.sub(a);
        }

        @Override
        public void drag(double x, double y) {
            float fraction;
            if (circular != null) {
                // The knob follows the hand round its centre: the angle the mouse sweeps is the angle it turns.
                if (Math.hypot(x - circular.x, y - circular.y) < KNOB_DEAD_ZONE) {
                    return;
                }
                float angle = (float) Math.atan2(y - circular.y, x - circular.x);
                float delta = angle - lastAngle;
                if (delta > Math.PI) {
                    delta -= (float) (Math.PI * 2.0);
                } else if (delta < -Math.PI) {
                    delta += (float) (Math.PI * 2.0);
                }
                lastAngle = angle;
                // Screen y points down: a growing angle is clockwise on the screen, which is a negative turn about an
                // axis pointing at the camera.
                turned += (float) Math.toDegrees(-delta) * handedness;
                float travel = control.range()[1] - control.range()[0];
                fraction = Math.abs(travel) < 1.0e-3F ? startFraction : startFraction + turned / travel;
            } else if (screenAxis != null) {
                float along = (float) ((x - startX) * screenAxis.x + (y - startY) * screenAxis.y) / screenAxis.lengthSquared();
                fraction = startFraction + along;
            } else {
                fraction = startFraction + (float) (((x - startX) - (y - startY)) / KNOB_TRAVEL);
            }
            // Move the control here at once; tell the logic at most every CONTROL_SEND_INTERVAL_MS and on release.
            setControl(station, node, control, spec, spec.valueAt(Mth.clamp(fraction, 0.0F, 1.0F)), false);
            flush(false);
        }

        private void flush(boolean force) {
            float value = station.state().getControl(control.control());
            long now = System.currentTimeMillis();
            if (value != sent && (force || now - lastSent >= CONTROL_SEND_INTERVAL_MS)) {
                lastSent = now;
                sent = value;
                station.perform(new StationAction.SetControl(control.control(), value));
            }
        }

        @Override
        public void frame() {
            flush(false);
        }

        @Override
        public void release() {
            flush(true);
        }

        @Override
        public void cancel() {
            flush(true);
        }

        @Override
        public Presence presence() {
            return null;
        }
    }
}
