package com.pockyl.diegesis.api.scene;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One object of a scene. A node has a place ({@link Transform}), optionally a look ({@link Visual}) and any number of
 * behaviours: a slot, an output, a control, a grabbable object. Children move with their parent.
 *
 * <p>In a scene file a node may start from a node template, {@code "template": "diegesis:control/knob"}: the fields of
 * {@code assets/<namespace>/diegesis/nodes/<path>.json} with the node's own fields laid over them (objects merge field
 * by field, {@code null} removes one). Diegesis ships ready controls: {@code diegesis:control/lever},
 * {@code diegesis:control/switch}, {@code diegesis:control/button}, {@code diegesis:control/knob},
 * {@code diegesis:control/slider} - give them an {@code id}, a {@code pos} and the {@code control} they drive.
 *
 * @param id        unique in the scene, {@code [a-z0-9_]+}; behaviours and code refer to nodes by it
 * @param transform the place relative to the parent node (or to the block)
 * @param visual    what is drawn; a node without one is an invisible pivot or hit zone
 * @param collider  what the cursor can hit, in the node's space; derived from the visual or behaviour when absent
 * @param trigger   the collider is only a zone carried objects touch ({@code SceneEvent.Contact}); the cursor passes
 *                  through it
 * @param pickable  false makes the node pure decoration: no hit box at all, the cursor and carried objects pass through
 *                  it (a needle, a label, a part drawn on a grabbable parent)
 * @param precise   a mesh visual is hit only where its polygons are, not anywhere in the collider: objects standing close
 *                  together, seen at an angle, do not catch clicks meant for each other
 * @param strokes   a press on the node starts a stroke: {@code SceneEvent.Stroke} every frame until the button is let go, the
 *                  cursor free to leave the node (shaping, painting, wiping); a role-less node only
 * @param dropZone  the name of a slot group: a stack clicked or let go on the node goes into that group, merging with what
 *                  is there or into the first empty slot, as into the mouth of a pot. The cursor passes through while the hand
 *                  is empty or nothing fits, so the slots behind it stay reachable
 * @param tooltip   text shown next to the cursor while it is over the node: a translation key (shown as written when
 *                  no language has it); code can change it ({@code NodeView.setTooltip}). A node with a tooltip and a
 *                  hit box can be hovered even when it does nothing else
 * @param grabbable makes the node an object the player can pick up and carry
 * @param slot      makes the node a place for an item of a station slot
 * @param output    makes the node show a station output, taken by clicking
 * @param control   makes the node a button, toggle, knob or slider driving a station control
 * @param tank      shows a station tank as liquid inside the node (may be combined with any role; with a spout it pours)
 * @param emitter   gives off particles
 * @param light     shines on the objects around it
 * @param hidden    starts invisible (code can show it later)
 * @param extra     free-form data for the mod's own code, read through the client node view
 * @param children  nodes attached to this one
 */
public record NodeDefinition(String id, Transform transform, Optional<Visual> visual, Optional<Box> collider, boolean trigger,
        boolean pickable, boolean precise, boolean strokes, Optional<String> dropZone, Optional<String> tooltip, Optional<Grabbable> grabbable, Optional<SlotBinding> slot, Optional<OutputBinding> output, Optional<ControlBinding> control,
        Optional<TankBinding> tank, Optional<EmitterDefinition> emitter, Optional<LightDefinition> light, boolean hidden,
        JsonObject extra, List<NodeDefinition> children) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    public static final Codec<String> ID_CODEC = Codec.STRING.comapFlatMap(id -> ID.matcher(id).matches() ? DataResult.success(id)
            : DataResult.error(() -> "Node ids are lower case letters, digits and _: " + id), id -> id);

    private static final Codec<JsonObject> JSON_OBJECT = ExtraCodecs.JSON.comapFlatMap(json -> json.isJsonObject()
            ? DataResult.success(json.getAsJsonObject()) : DataResult.error(() -> "\"extra\" must be an object"), json -> json);

    /** The node's switches, read as one part of the node object (a record codec takes at most 16 fields). */
    private record Flags(boolean trigger, boolean pickable, boolean precise, boolean strokes, boolean hidden, Optional<String> tooltip,
            Optional<String> dropZone) {
        static final MapCodec<Flags> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                StrictCodecs.optional(Codec.BOOL, "trigger", false).forGetter(Flags::trigger),
                StrictCodecs.optional(Codec.BOOL, "pickable", true).forGetter(Flags::pickable),
                StrictCodecs.optional(Codec.BOOL, "precise", false).forGetter(Flags::precise),
                StrictCodecs.optional(Codec.BOOL, "strokes", false).forGetter(Flags::strokes),
                StrictCodecs.optional(Codec.BOOL, "hidden", false).forGetter(Flags::hidden),
                StrictCodecs.optional(Codec.STRING, "tooltip").forGetter(Flags::tooltip),
                StrictCodecs.optional(Codec.STRING, "drop_zone").forGetter(Flags::dropZone)
        ).apply(instance, Flags::new));
    }

    public static final Codec<NodeDefinition> CODEC = Codec.lazyInitialized(() -> StrictCodecs.<NodeDefinition>record(instance -> instance.group(
            ID_CODEC.fieldOf("id").forGetter(NodeDefinition::id),
            Transform.MAP_CODEC.forGetter(NodeDefinition::transform),
            StrictCodecs.optional(Visual.CODEC, "visual").forGetter(NodeDefinition::visual),
            StrictCodecs.optional(Box.CODEC, "collider").forGetter(NodeDefinition::collider),
            Flags.MAP_CODEC.forGetter(node -> new Flags(node.trigger, node.pickable, node.precise, node.strokes, node.hidden, node.tooltip, node.dropZone)),
            StrictCodecs.optional(Grabbable.CODEC, "grabbable").forGetter(NodeDefinition::grabbable),
            StrictCodecs.optional(SlotBinding.CODEC, "slot").forGetter(NodeDefinition::slot),
            StrictCodecs.optional(OutputBinding.CODEC, "output").forGetter(NodeDefinition::output),
            StrictCodecs.optional(ControlBinding.CODEC, "control").forGetter(NodeDefinition::control),
            StrictCodecs.optional(TankBinding.CODEC, "tank").forGetter(NodeDefinition::tank),
            StrictCodecs.optional(EmitterDefinition.CODEC, "emitter").forGetter(NodeDefinition::emitter),
            StrictCodecs.optional(LightDefinition.CODEC, "light").forGetter(NodeDefinition::light),
            StrictCodecs.optional(JSON_OBJECT, "extra", new JsonObject()).forGetter(NodeDefinition::extra),
            StrictCodecs.optional(NodeDefinition.CODEC.listOf(), "children", List.of()).forGetter(NodeDefinition::children)
    ).apply(instance, (id, transform, visual, collider, flags, grabbable, slot, output, control, tank, emitter, light, extra, children) ->
            new NodeDefinition(id, transform, visual, collider, flags.trigger, flags.pickable, flags.precise, flags.strokes, flags.dropZone, flags.tooltip, grabbable, slot, output,
                    control, tank, emitter, light, flags.hidden, extra, children))).comapFlatMap(NodeDefinition::validate, node -> node));

    public NodeDefinition {
        extra = extra.deepCopy();
        children = List.copyOf(children);
    }

    /** Starts a node in code; the same as writing it in a scene file. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Whether the player can do anything with the node. */
    public boolean isInteractive() {
        return grabbable.isPresent() || slot.isPresent() || output.isPresent() || control.isPresent();
    }

    private static DataResult<NodeDefinition> validate(NodeDefinition node) {
        int roles = (node.grabbable.isPresent() ? 1 : 0) + (node.slot.isPresent() ? 1 : 0) + (node.output.isPresent() ? 1 : 0)
                + (node.control.isPresent() ? 1 : 0);
        if (roles > 1) {
            return DataResult.error(() -> "Node \"" + node.id + "\" has more than one of grabbable, slot, output, control;"
                    + " put the others on child nodes");
        }
        if (node.strokes && (roles > 0 || !node.pickable || node.trigger)) {
            return DataResult.error(() -> "Node \"" + node.id + "\" takes strokes, so it has no role and can be hit");
        }
        if (node.dropZone.isPresent() && (roles > 0 || node.strokes || !node.pickable || node.trigger)) {
            return DataResult.error(() -> "Node \"" + node.id + "\" is a drop zone, so it has no role and can be hit");
        }
        if (!node.pickable && (roles > 0 || node.trigger)) {
            return DataResult.error(() -> "Node \"" + node.id + "\" is not pickable but has a role or is a trigger; a node the"
                    + " cursor or carried objects cannot reach could never be used");
        }
        return DataResult.success(node);
    }

    /** Builds a node in code. */
    public static final class Builder {
        private final String id;
        private Transform transform = Transform.IDENTITY;
        private Visual visual;
        private Box collider;
        private Grabbable grabbable;
        private SlotBinding slot;
        private OutputBinding output;
        private ControlBinding control;
        private TankBinding tank;
        private EmitterDefinition emitter;
        private LightDefinition light;
        private boolean trigger;
        private boolean pickable = true;
        private boolean precise;
        private boolean strokes;
        private String dropZone;
        private String tooltip;
        private boolean hidden;
        private JsonObject extra = new JsonObject();
        private final List<NodeDefinition> children = new ArrayList<>();

        private Builder(String id) {
            this.id = ID_CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(id)).getOrThrow();
        }

        public Builder at(float x, float y, float z) {
            transform = transform.withPos(new org.joml.Vector3f(x, y, z));
            return this;
        }

        public Builder transform(Transform value) {
            transform = value;
            return this;
        }

        public Builder visual(Visual value) {
            visual = value;
            return this;
        }

        public Builder collider(Box value) {
            collider = value;
            return this;
        }

        public Builder grabbable(Grabbable value) {
            grabbable = value;
            return this;
        }

        public Builder slot(SlotBinding value) {
            slot = value;
            return this;
        }

        public Builder output(OutputBinding value) {
            output = value;
            return this;
        }

        public Builder control(ControlBinding value) {
            control = value;
            return this;
        }

        public Builder tank(TankBinding value) {
            tank = value;
            return this;
        }

        public Builder emitter(EmitterDefinition value) {
            emitter = value;
            return this;
        }

        public Builder light(LightDefinition value) {
            light = value;
            return this;
        }

        /** A mesh visual is hit on its own polygons, not anywhere in the collider. */
        public Builder precise() {
            precise = true;
            return this;
        }

        /** A press on the node starts a stroke, see {@code SceneEvent.Stroke}. */
        public Builder strokes() {
            strokes = true;
            return this;
        }

        /** Stacks clicked or let go on the node go into this slot group, see {@link NodeDefinition#dropZone}. */
        public Builder dropZone(String slotGroup) {
            dropZone = slotGroup;
            return this;
        }

        /** The collider is a trigger zone for carried objects only; the cursor passes through it. */
        public Builder trigger() {
            trigger = true;
            return this;
        }

        /** Pure decoration: no hit box, the cursor and carried objects pass through it. */
        public Builder notPickable() {
            pickable = false;
            return this;
        }

        /** Text next to the cursor while it is over the node: a translation key. */
        public Builder tooltip(String translationKey) {
            tooltip = translationKey;
            return this;
        }

        public Builder hidden() {
            hidden = true;
            return this;
        }

        public Builder extra(JsonObject value) {
            extra = value;
            return this;
        }

        public Builder child(NodeDefinition child) {
            children.add(child);
            return this;
        }

        public NodeDefinition build() {
            NodeDefinition node = new NodeDefinition(id, transform, Optional.ofNullable(visual), Optional.ofNullable(collider), trigger,
                    pickable, precise, strokes, Optional.ofNullable(dropZone), Optional.ofNullable(tooltip), Optional.ofNullable(grabbable), Optional.ofNullable(slot), Optional.ofNullable(output), Optional.ofNullable(control),
                    Optional.ofNullable(tank), Optional.ofNullable(emitter), Optional.ofNullable(light), hidden, extra, children);
            return validate(node).getOrThrow();
        }
    }
}
