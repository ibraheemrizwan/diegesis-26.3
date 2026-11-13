package com.pockyl.diegesis.impl.client.scene;

import net.minecraft.world.entity.player.Inventory;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.ControlBinding;
import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.scene.InventoryLayout;
import com.pockyl.diegesis.api.scene.LightDefinition;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.scene.SlotBinding;
import com.pockyl.diegesis.api.state.ControlSpec;
import com.pockyl.diegesis.api.state.StationSchema.SlotGroup;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.impl.client.ClientStation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The node tree of one shown station, built from its {@link SceneDefinition}. */
public final class SceneInstance {
    private final ClientStation station;
    private final SceneDefinition definition;
    private final List<NodeInstance> roots = new ArrayList<>();
    private final Map<String, NodeInstance> byId = new LinkedHashMap<>();
    /** One node per slot of the placing's group: a scene file does not know the items that will stand there. */
    private final List<NodeInstance> placed = new ArrayList<>();
    /** Station-space matrices of the 36 inventory slots laid out in the scene; null without a layout. */
    private final Matrix4f[] inventorySlots;

    public SceneInstance(ClientStation station, SceneDefinition definition) {
        this.station = station;
        this.definition = definition;
        this.inventorySlots = definition.inventory().map(SceneInstance::slotMatrices).orElse(null);
        for (NodeDefinition node : definition.nodes()) {
            NodeInstance instance = new NodeInstance(this, node, null);
            roots.add(instance);
            index(instance);
        }
        definition.placing().ifPresent(this::addPlaced);
    }

    private void addPlaced(PlacingOptions options) {
        SlotGroup group = station.type().schema().slotGroups().get(options.group());
        if (group == null || !group.rules().handPlaced()) {
            DiegesisMod.LOGGER.error("Station {}: \"placing\" needs a slot group \"{}\" with placed() rules", station.type().id(), options.group());
            return;
        }
        for (int i = 0; i < group.size(); i++) {
            NodeDefinition node = NodeDefinition.builder("placed_" + i)
                    .slot(new SlotBinding(group.ref(i), SlotBinding.ItemDisplay.UPRIGHT, 6.0F, java.util.Optional.empty(), SlotBinding.Counts.ALWAYS, true))
                    .light(new LightDefinition(0, options.lightRadius(), options.lightColor(), 0.12F, new Vector3f())).hidden().build();
            NodeInstance instance = new NodeInstance(this, node, null);
            instance.setPlaced(new PlacedItem(group.ref(i), options));
            placed.add(instance);
            roots.add(instance);
            index(instance);
        }
    }

    /** The nodes of the placing's slots, also those that are empty now. */
    public List<NodeInstance> placedNodes() {
        return placed;
    }

    private void index(NodeInstance node) {
        byId.put(node.id(), node);
        node.children().forEach(this::index);
    }

    public ClientStation station() {
        return station;
    }

    public SceneDefinition definition() {
        return definition;
    }

    public NodeInstance node(String id) {
        return byId.get(id);
    }

    /** Every node, parents before children. */
    public Iterable<NodeInstance> nodes() {
        return Collections.unmodifiableCollection(byId.values());
    }

    private static Matrix4f[] slotMatrices(InventoryLayout layout) {
        Matrix4f grid = layout.matrix();
        Matrix4f[] slots = new Matrix4f[Inventory.INVENTORY_SIZE];
        for (int slot = 0; slot < slots.length; slot++) {
            Vector3f offset = layout.slotOffset(slot);
            slots[slot] = new Matrix4f(grid).translate(offset);
        }
        return slots;
    }

    /** The scene's inventory layout, or null when the inventory is the panel on the screen. */
    public InventoryLayout inventoryLayout() {
        return definition.inventory().orElse(null);
    }

    /** The station-space matrix of an inventory slot laid out in the scene. */
    public Matrix4f inventorySlot(int slot) {
        return inventorySlots[slot];
    }

    /** The nearest inventory slot under a ray in station space, or null (also without a layout). */
    public InventoryHit pickInventory(Vector3fc origin, Vector3fc direction) {
        if (inventorySlots == null) {
            return null;
        }
        float half = definition.inventory().get().spacing() / 2.0F;
        Box box = new Box(new Vector3f(-half, 0.0F, -half), new Vector3f(half, 0.6F, half));
        InventoryHit best = null;
        Matrix4f inverse = new Matrix4f();
        Vector3f localOrigin = new Vector3f();
        Vector3f localDirection = new Vector3f();
        for (int slot = 0; slot < inventorySlots.length; slot++) {
            inventorySlots[slot].invert(inverse);
            inverse.transformPosition(origin, localOrigin);
            inverse.transformDirection(direction, localDirection);
            float t = intersect(box, localOrigin, localDirection);
            if (t >= 0.0F && (best == null || t < best.distance())) {
                best = new InventoryHit(slot, t);
            }
        }
        return best;
    }

    /** An inventory slot under a ray: {@code distance} is the ray parameter, as for {@link Hit}. */
    public record InventoryHit(int slot, float distance) {
    }

    /** Moves controls to the state's values and recomputes every node's matrix. */
    public void update(StationState state, float dt) {
        for (NodeInstance node : byId.values()) {
            node.definition().control().ifPresent(binding -> {
                ControlSpec spec = state.schema().controls().get(binding.control());
                if (spec != null && binding.type() != ControlBinding.Type.BUTTON) {
                    node.setControlTarget(spec.fraction(state.getControl(binding.control())));
                }
            });
        }
        for (NodeInstance node : placed) {
            boolean there = node.placed().sync(state);
            node.setVisible(there);
            node.setLight(there && node.placed().options().itemLight() ? node.placed().shape().light() : 0);
        }
        for (NodeInstance root : roots) {
            root.update(dt);
        }
    }

    /**
     * The nearest visible node hit by a ray in station space that passes {@code filter}, or null.
     *
     * @param origin    ray start in station pixels
     * @param direction ray direction in station pixels (any length)
     */
    public Hit pick(Vector3fc origin, Vector3fc direction, Predicate<NodeInstance> filter) {
        Hit best = null;
        Matrix4f inverse = new Matrix4f();
        Vector3f localOrigin = new Vector3f();
        Vector3f localDirection = new Vector3f();
        for (NodeInstance node : byId.values()) {
            Box box = node.collider();
            if (box == null || !node.isVisible() || !filter.test(node)) {
                continue;
            }
            node.pickMatrix().invert(inverse);
            inverse.transformPosition(origin, localOrigin);
            inverse.transformDirection(direction, localDirection);
            float t = intersect(box, localOrigin, localDirection);
            if (t >= 0.0F && node.definition().precise()) {
                t = node.preciseHit(localOrigin, localDirection, t);
            }
            // The parameter t is the same in every space: both ends of the ray are transformed linearly.
            if (t >= 0.0F && (best == null || t < best.distance())) {
                Vector3f point = new Vector3f(direction).mul(t).add(origin);
                best = new Hit(node, t, point);
            }
        }
        return best;
    }

    /** Ray parameter of the entry into a box (slab method), or -1. */
    private static float intersect(Box box, Vector3fc origin, Vector3fc direction) {
        float tMin = 0.0F;
        float tMax = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            float o = origin.get(axis);
            float d = direction.get(axis);
            float min = box.from().get(axis);
            float max = box.to().get(axis);
            if (Math.abs(d) < 1.0e-7F) {
                if (o < min || o > max) {
                    return -1.0F;
                }
                continue;
            }
            float t1 = (min - o) / d;
            float t2 = (max - o) / d;
            if (t1 > t2) {
                float swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0F;
            }
        }
        return tMin;
    }

    /** A node under a ray: {@code distance} is the ray parameter, {@code point} the hit point in station space. */
    public record Hit(NodeInstance node, float distance, Vector3f point) {
    }
}
