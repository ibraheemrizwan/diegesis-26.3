package com.pockyl.diegesis.api.event;

import org.joml.Vector3fc;

/**
 * Something the local player did in a scene, reported to the station's client behaviour. State changes (items, control
 * values) arrive separately through {@code onStateChanged}, for every player's actions.
 */
public sealed interface SceneEvent {
    /** The cursor moved onto a node ({@code node} set) or off every node ({@code node} null). */
    record Hover(String node) implements SceneEvent {
    }

    /** A node without any role, but with a collider, was clicked. */
    record Click(String node, int button) implements SceneEvent {
    }

    /**
     * The mouse working on a node with {@code "strokes": true}: while a button is held this comes every frame, so a hand held still
     * goes on working, and {@code HOVER} comes every frame while the cursor is over the node with no button down, to show what a
     * press would do. {@code point} is where the cursor is on the upright plane through the node's origin that faces the camera,
     * in scene pixels: it is there also beside the node and in the air.
     */
    record Stroke(String node, Phase phase, int button, Vector3fc point) implements SceneEvent {
        /** A stroke starts once, moves every frame and ends once; hovering comes before it. */
        public enum Phase {
            HOVER, START, MOVE, END
        }
    }

    /** A grabbable node was picked up. */
    record Grab(String node) implements SceneEvent {
    }

    /**
     * A grabbable node was let go; {@code target} is the node under it at that moment (null when none): the hook for
     * "pour this vial into that flask" style interactions.
     */
    record Release(String node, String target) implements SceneEvent {
    }

    /**
     * A carried node started ({@code touching} true) or stopped touching another node's hit box: the hook for trigger
     * zones (a node with a {@code collider} and nothing else) and for knocks between objects. {@code speed} is how
     * fast the carried node moved, in pixels per second.
     */
    record Contact(String node, String other, boolean touching, float speed) implements SceneEvent {
    }

    /**
     * The vessel the local player holds started ({@code pouring} true) or stopped pouring into another tank; the amounts
     * reach the logic through {@code StationLogic.onPoured}.
     */
    record Pour(String node, String target, boolean pouring) implements SceneEvent {
    }

    /** The inventory drawer slid out ({@code open} true) or back. */
    record InventoryDrawer(boolean open) implements SceneEvent {
    }

    /** The camera set off for a glance of the rig ({@code looking} true) or back from it. */
    record Glance(String glance, boolean looking) implements SceneEvent {
    }

    /** A control was moved by the local player; {@code value} is the new value (also sent to the server). */
    record ControlMoved(String control, float value) implements SceneEvent {
    }

    /** A button control was pressed by the local player. */
    record ButtonPressed(String control) implements SceneEvent {
    }
}
