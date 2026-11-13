package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * Makes a node a control the player operates with the mouse. The node moves with the control's value: a knob or a
 * toggle turns around {@code axis} from {@code range[0]} to {@code range[1]} degrees, a slider or a button moves along
 * {@code axis} by that many pixels. Axis, range and pivot are in the node's own space - after its rotation and scale -
 * so a control scaled down travels with its model.
 *
 * @param control the station control the node drives, declared in the station's schema
 * @param type    how the player operates it
 * @param axis    the turn axis or the direction of travel, in the node's own space
 * @param range   the movement at the control's minimum and maximum value
 * @param circular a knob is turned by moving the mouse round it, grabbed anywhere, and follows the hand; false turns
 *                 it by dragging up or to the right
 * @param pivot    the point a knob or toggle turns round, in the node's pixels (a lever's hinge); default the node's
 *                 origin
 * @param base     what stays still while the node moves: a lever's plate, a slider's track, a knob's dial face; drawn
 *                 where the node rests (its {@code pos}, {@code rotation} and {@code scale}), not clickable
 * @param smoothing seconds the node takes to cover half the way to a new value (0.05: it follows the hand). A larger
 *                  value glides: a gauge needle bound to a locked control the logic sets in steps
 */
public record ControlBinding(String control, Type type, Vector3fc axis, float[] range, boolean circular, Vector3fc pivot,
        Optional<Visual> base, float smoothing) {
    /** How fast a control node follows its value by default: half the way in this many seconds. */
    public static final float DEFAULT_SMOOTHING = 0.05F;

    public static final Codec<ControlBinding> CODEC = StrictCodecs.record(instance -> instance.group(
            Codec.STRING.fieldOf("control").forGetter(ControlBinding::control),
            Type.CODEC.fieldOf("type").forGetter(ControlBinding::type),
            StrictCodecs.optional(StrictCodecs.VEC3, "axis").forGetter(b -> Optional.of(new Vector3f(b.axis))),
            StrictCodecs.optional(StrictCodecs.RANGE, "range").forGetter(b -> Optional.of(b.range)),
            StrictCodecs.optional(Codec.BOOL, "circular", true).forGetter(ControlBinding::circular),
            StrictCodecs.optional(StrictCodecs.VEC3, "pivot", new Vector3f()).forGetter(b -> new Vector3f(b.pivot)),
            StrictCodecs.optional(Visual.CODEC, "base").forGetter(ControlBinding::base),
            StrictCodecs.optional(Codec.floatRange(0.0F, 10.0F), "smoothing", DEFAULT_SMOOTHING).forGetter(ControlBinding::smoothing)
    ).apply(instance, (control, type, axis, range, circular, pivot, base, smoothing) -> new ControlBinding(control, type,
            axis.orElseGet(() -> new Vector3f(type.defaultAxis)), range.orElseGet(type.defaultRange::clone), circular, pivot, base,
            smoothing)));


    public ControlBinding {
        axis = new Vector3f(axis).normalize();
        if (!Float.isFinite(axis.x())) {
            throw new IllegalArgumentException("Control axis must not be zero");
        }
        range = range.clone();
        pivot = new Vector3f(pivot);
    }

    public static ControlBinding of(String control, Type type) {
        return new ControlBinding(control, type, type.defaultAxis, type.defaultRange, true, new Vector3f(), Optional.empty(),
                DEFAULT_SMOOTHING);
    }

    /** The same control with a part that stays still while the node moves. */
    public ControlBinding withBase(Visual visual) {
        return new ControlBinding(control, type, axis, range, circular, pivot, Optional.of(visual), smoothing);
    }

    /** The same control gliding to new values (see {@code smoothing}). */
    public ControlBinding withSmoothing(float seconds) {
        return new ControlBinding(control, type, axis, range, circular, pivot, base, seconds);
    }

    /** The movement for a value between 0 and 1 of the control's range. */
    public float movement(float fraction) {
        return range[0] + (range[1] - range[0]) * fraction;
    }

    /** How a control is operated. */
    public enum Type implements StringRepresentable {
        /** Clicked; springs back. Its value counts presses. */
        BUTTON("button", new Vector3f(0.0F, -1.0F, 0.0F), new float[] {0.0F, 1.0F}),
        /** Clicked to flip between off (0) and on (1), like a lever or a switch. */
        TOGGLE("toggle", new Vector3f(1.0F, 0.0F, 0.0F), new float[] {-25.0F, 25.0F}),
        /** Turned by dragging or with the mouse wheel, like a gas valve or a dial. */
        KNOB("knob", new Vector3f(0.0F, 1.0F, 0.0F), new float[] {135.0F, -135.0F}),
        /** Dragged along a straight track. */
        SLIDER("slider", new Vector3f(1.0F, 0.0F, 0.0F), new float[] {0.0F, 8.0F});

        public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

        private final String name;
        private final Vector3fc defaultAxis;
        private final float[] defaultRange;

        Type(String name, Vector3fc defaultAxis, float[] defaultRange) {
            this.name = name;
            this.defaultAxis = defaultAxis;
            this.defaultRange = defaultRange;
        }

        /** Whether the node turns (knob, toggle) rather than moves (slider, button). */
        public boolean rotates() {
            return this == KNOB || this == TOGGLE;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
