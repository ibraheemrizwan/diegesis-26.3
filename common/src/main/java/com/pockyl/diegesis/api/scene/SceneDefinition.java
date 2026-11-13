package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything a station shows and lets the player touch: the camera rig and a tree of nodes. Usually written as a JSON
 * file, {@code assets/<namespace>/diegesis/scenes/<path>.json}, and reloaded with the resource packs (F3+T), so it can
 * be tuned while the game runs; it can also be built in code with {@link #builder()}.
 *
 * <p>Coordinates are Blockbench pixels relative to the block's corner, for a block facing north.
 *
 * @param camera     where the camera goes while the player works here
 * @param workPlane  the height (pixels) of the surface carried objects move over, usually the table top
 * @param idleVisible whether the nodes are drawn while nobody works at the block (needs a block entity)
 * @param nodes      the top-level nodes
 * @param inventory  where the player's inventory lies in the scene; without one it is the vanilla panel on the screen
 * @param ui         how the interface behaves and looks ({@code "interface"} in the file)
 * @param carryPlanes levels carried objects rise to over parts of the scene (over a tall vessel), see {@link CarryPlane}
 * @param inventoryDrawer keeps the inventory in a drawer at a side of the screen; not together with {@code inventory}
 * @param pouring    how pouring looks
 * @param captions   how the lines of {@code StationView.showCaption} look
 * @param placing    lets items be put down anywhere on the scene's surfaces, turned and sized freely
 * @param clearView  what stands between the camera and the station dissolves while the player works here
 * @param gains      how what the player gets is seen going into the inventory
 * @param putAway    a right click with a stack on the cursor that nothing in the scene takes puts the stack back into the
 *                   inventory, to the slot it came from while that is empty (owner, 2026-11-08: "a right click must put away
 *                   what is in the hand, a knife for one")
 */
public record SceneDefinition(CameraRig camera, float workPlane, boolean idleVisible, List<NodeDefinition> nodes,
        Optional<InventoryLayout> inventory, InterfaceOptions ui, List<CarryPlane> carryPlanes, Optional<InventoryDrawer> inventoryDrawer,
        PourOptions pouring, CaptionOptions captions, Optional<PlacingOptions> placing, ClearViewOptions clearView, GainOptions gains,
        boolean putAway) {
    public static final Codec<SceneDefinition> CODEC = StrictCodecs.<SceneDefinition>record(instance -> instance.group(
            StrictCodecs.optional(CameraRig.CODEC, "camera", CameraRig.DEFAULT).forGetter(SceneDefinition::camera),
            StrictCodecs.optional(Codec.FLOAT, "work_plane", 16.0F).forGetter(SceneDefinition::workPlane),
            StrictCodecs.optional(Codec.BOOL, "idle_visible", true).forGetter(SceneDefinition::idleVisible),
            StrictCodecs.optional(NodeDefinition.CODEC.listOf(), "nodes", List.of()).forGetter(SceneDefinition::nodes),
            StrictCodecs.optional(InventoryLayout.CODEC, "inventory").forGetter(SceneDefinition::inventory),
            StrictCodecs.optional(InterfaceOptions.CODEC, "interface", InterfaceOptions.DEFAULT).forGetter(SceneDefinition::ui),
            StrictCodecs.optional(CarryPlane.CODEC.listOf(), "carry_planes", List.of()).forGetter(SceneDefinition::carryPlanes),
            StrictCodecs.optional(InventoryDrawer.CODEC, "inventory_drawer").forGetter(SceneDefinition::inventoryDrawer),
            StrictCodecs.optional(PourOptions.CODEC, "pouring", PourOptions.DEFAULT).forGetter(SceneDefinition::pouring),
            StrictCodecs.optional(CaptionOptions.CODEC, "captions", CaptionOptions.DEFAULT).forGetter(SceneDefinition::captions),
            StrictCodecs.optional(PlacingOptions.CODEC, "placing").forGetter(SceneDefinition::placing),
            StrictCodecs.optional(ClearViewOptions.CODEC, "clear_view", ClearViewOptions.DEFAULT).forGetter(SceneDefinition::clearView),
            StrictCodecs.optional(GainOptions.CODEC, "gains", GainOptions.DEFAULT).forGetter(SceneDefinition::gains),
            StrictCodecs.optional(Codec.BOOL, "put_away", true).forGetter(SceneDefinition::putAway)
    ).apply(instance, SceneDefinition::new)).comapFlatMap(SceneDefinition::validate, scene -> scene);

    public SceneDefinition {
        nodes = List.copyOf(nodes);
        carryPlanes = List.copyOf(carryPlanes);
    }

    /** A scene where a right click puts the cursor's stack away. */
    public SceneDefinition(CameraRig camera, float workPlane, boolean idleVisible, List<NodeDefinition> nodes, Optional<InventoryLayout> inventory,
            InterfaceOptions ui, List<CarryPlane> carryPlanes, Optional<InventoryDrawer> inventoryDrawer, PourOptions pouring,
            CaptionOptions captions, Optional<PlacingOptions> placing, ClearViewOptions clearView, GainOptions gains) {
        this(camera, workPlane, idleVisible, nodes, inventory, ui, carryPlanes, inventoryDrawer, pouring, captions, placing, clearView, gains,
                true);
    }

    /** A scene with the default {@link GainOptions}. */
    public SceneDefinition(CameraRig camera, float workPlane, boolean idleVisible, List<NodeDefinition> nodes, Optional<InventoryLayout> inventory,
            InterfaceOptions ui, List<CarryPlane> carryPlanes, Optional<InventoryDrawer> inventoryDrawer, PourOptions pouring,
            CaptionOptions captions, Optional<PlacingOptions> placing, ClearViewOptions clearView) {
        this(camera, workPlane, idleVisible, nodes, inventory, ui, carryPlanes, inventoryDrawer, pouring, captions, placing, clearView,
                GainOptions.DEFAULT);
    }

    /** How high (pixels) the grip of the grabbable node {@code node} is carried at {@code x, z}. */
    public float carryHeight(String node, float lift, float x, float z) {
        float base = workPlane + lift;
        float height = base;
        for (CarryPlane plane : carryPlanes) {
            if (plane.appliesTo(node)) {
                height = Math.max(height, plane.heightAt(x, z, base));
            }
        }
        return height;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Calls {@code action} for every node, parents before children. */
    public void forEachNode(Consumer<NodeDefinition> action) {
        nodes.forEach(node -> visit(node, action));
    }

    private static void visit(NodeDefinition node, Consumer<NodeDefinition> action) {
        action.accept(node);
        node.children().forEach(child -> visit(child, action));
    }

    private static DataResult<SceneDefinition> validate(SceneDefinition scene) {
        Set<String> ids = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        scene.forEachNode(node -> {
            if (!ids.add(node.id())) {
                duplicates.add(node.id());
            }
        });
        if (!duplicates.isEmpty()) {
            return DataResult.error(() -> "Duplicate node ids: " + duplicates);
        }
        if (scene.inventory.isPresent() && scene.inventoryDrawer.isPresent()) {
            return DataResult.error(() -> "A scene has either \"inventory\" or \"inventory_drawer\", not both");
        }
        return validateGlances(scene);
    }

    private static DataResult<SceneDefinition> validateGlances(SceneDefinition scene) {
        Set<String> glances = new HashSet<>();
        Set<InventoryDrawer.Side> edges = new HashSet<>();
        scene.inventoryDrawer.ifPresent(drawer -> edges.add(drawer.side()));
        for (CameraGlance glance : scene.camera.glances()) {
            if (!glances.add(glance.id())) {
                return DataResult.error(() -> "Duplicate glance id: " + glance.id());
            }
            if (glance.edge().isPresent() && !edges.add(glance.edge().get())) {
                return DataResult.error(() -> "The " + glance.edge().get().getSerializedName()
                        + " edge of the screen is taken twice: glance \"" + glance.id() + "\" needs one of its own");
            }
        }
        return DataResult.success(scene);
    }

    /** Builds a scene in code. */
    public static final class Builder {
        private CameraRig camera = CameraRig.DEFAULT;
        private float workPlane = 16.0F;
        private boolean idleVisible = true;
        private final List<NodeDefinition> nodes = new ArrayList<>();
        private InventoryLayout inventory;
        private InterfaceOptions ui = InterfaceOptions.DEFAULT;
        private final List<CarryPlane> carryPlanes = new ArrayList<>();
        private InventoryDrawer inventoryDrawer;
        private PourOptions pouring = PourOptions.DEFAULT;
        private CaptionOptions captions = CaptionOptions.DEFAULT;
        private PlacingOptions placing;
        private ClearViewOptions clearView = ClearViewOptions.DEFAULT;
        private GainOptions gains = GainOptions.DEFAULT;
        private boolean putAway = true;

        private Builder() {
        }

        /** Whether a right click with a stack on the cursor that nothing takes puts it back into the inventory. */
        public Builder putAway(boolean value) {
            putAway = value;
            return this;
        }

        /** How what the player gets is seen going into the inventory; {@link GainOptions#OFF}: not at all. */
        public Builder gains(GainOptions value) {
            gains = value;
            return this;
        }

        /** What dissolves between the camera and the station; {@link ClearViewOptions#OFF} leaves everything as it is. */
        public Builder clearView(ClearViewOptions value) {
            clearView = value;
            return this;
        }

        /** How the interface behaves and looks; see {@link InterfaceOptions#builder()}. */
        public Builder ui(InterfaceOptions value) {
            ui = value;
            return this;
        }

        /** A level carried objects rise to over part of the scene. */
        public Builder carryPlane(CarryPlane value) {
            carryPlanes.add(value);
            return this;
        }

        /** Lays the player's inventory out in the scene instead of the screen. */
        public Builder inventory(InventoryLayout value) {
            inventory = value;
            return this;
        }

        /** Keeps the player's inventory in a drawer at a side of the screen. */
        public Builder inventoryDrawer(InventoryDrawer value) {
            inventoryDrawer = value;
            return this;
        }

        /** How pouring looks. */
        public Builder pouring(PourOptions value) {
            pouring = value;
            return this;
        }

        /** Lets items be put down anywhere on surfaces of the scene. */
        public Builder placing(PlacingOptions value) {
            placing = value;
            return this;
        }

        /** How captions look. */
        public Builder captions(CaptionOptions value) {
            captions = value;
            return this;
        }

        public Builder camera(CameraRig value) {
            camera = value;
            return this;
        }

        public Builder workPlane(float pixels) {
            workPlane = pixels;
            return this;
        }

        public Builder idleVisible(boolean value) {
            idleVisible = value;
            return this;
        }

        public Builder node(NodeDefinition node) {
            nodes.add(node);
            return this;
        }

        public SceneDefinition build() {
            SceneDefinition scene = new SceneDefinition(camera, workPlane, idleVisible, nodes, Optional.ofNullable(inventory), ui,
                    carryPlanes, Optional.ofNullable(inventoryDrawer), pouring, captions, Optional.ofNullable(placing), clearView, gains,
                    putAway);
            return validate(scene).getOrThrow();
        }
    }
}
