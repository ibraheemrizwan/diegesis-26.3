package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * Makes a node an object the player picks up with the cursor and carries over the work surface: it lifts, follows the
 * cursor with a little lag, leans into its movement and casts a shadow.
 *
 * @param lift      how high its grip point is held above the work surface, in pixels (see the scene's carry planes)
 * @param sway      how much it leans while moving; 0 keeps it upright
 * @param stay      whether it stays where it is dropped instead of returning to its place
 * @param grip      the point it is held by, in the node's pixels: a vial by its top; the middle of its hit box by default
 * @param plane     a plane of its own it moves in instead of the work surface (lift and carry planes then do not apply)
 * @param contact   the part that touches other things ({@code SceneEvent.Contact}), in the node's pixels: a torch's flame,
 *                  a blade's edge; the whole hit box when empty
 * @param hold      how the hand keeps it: {@code auto} (a quick click leaves it in the hand until the next click, pressing
 *                  and dragging carries it until the button is let go), {@code drag} or {@code click} (only that way)
 */
public record Grabbable(float lift, float sway, boolean stay, Optional<Vector3fc> grip, Optional<GrabPlane> plane, Optional<Box> contact,
        Hold hold) {
    public static final Grabbable DEFAULT = new Grabbable(4.0F, 1.0F, false, Optional.empty(), Optional.empty(), Optional.empty(), Hold.AUTO);

    public static final Codec<Grabbable> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Codec.floatRange(0.0F, 64.0F), "lift", DEFAULT.lift).forGetter(Grabbable::lift),
            StrictCodecs.optional(Codec.floatRange(0.0F, 4.0F), "sway", DEFAULT.sway).forGetter(Grabbable::sway),
            StrictCodecs.optional(Codec.BOOL, "stay", DEFAULT.stay).forGetter(Grabbable::stay),
            StrictCodecs.optional(StrictCodecs.VEC3, "grip").forGetter(g -> g.grip.map(Vector3f::new)),
            StrictCodecs.optional(GrabPlane.CODEC, "plane").forGetter(Grabbable::plane),
            StrictCodecs.optional(Box.CODEC, "contact").forGetter(Grabbable::contact),
            StrictCodecs.optional(Hold.CODEC, "hold", DEFAULT.hold).forGetter(Grabbable::hold)
    ).apply(instance, (lift, sway, stay, grip, plane, contact, hold) -> new Grabbable(lift, sway, stay, grip.map(v -> (Vector3fc) v), plane,
            contact, hold)));

    /** How the hand keeps a grabbed object. */
    public enum Hold implements StringRepresentable {
        /** A quick click keeps it in the hand; pressing and dragging carries it until the button is let go. */
        AUTO("auto"),
        /** Only while the button is held down. */
        DRAG("drag"),
        /** A click takes it, the next click lets it go. */
        CLICK("click");

        public static final Codec<Hold> CODEC = StringRepresentable.fromEnum(Hold::values);

        private final String name;

        Hold(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public Grabbable {
        grip = grip.map(Vector3f::new);
    }
}
