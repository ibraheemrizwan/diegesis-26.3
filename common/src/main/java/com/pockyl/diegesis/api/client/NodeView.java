package com.pockyl.diegesis.api.client;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.Visual;

/**
 * A node of a shown scene, for client code to animate. Offsets, extra rotation and scale are added on top of the node's
 * place from the scene file and of what the player does with it; they belong to this client only.
 */
public interface NodeView {
    String id();

    NodeDefinition definition();

    /** The node's free-form {@code extra} data from the scene file. */
    default JsonObject extra() {
        return definition().extra();
    }

    boolean isVisible();

    /** Shows or hides the node and its children. */
    void setVisible(boolean visible);

    /** Moves the node by {@code x, y, z} pixels from its place, over {@code seconds} (0 = at once), eased. */
    void moveTo(float x, float y, float z, float seconds);

    /**
     * Moves the node like {@link #moveTo}, but over an arc: {@code height} pixels above the straight way at the middle. A
     * game piece picked up and set down, a thing tossed across the table.
     */
    void hopTo(float x, float y, float z, float height, float seconds);

    /** Whether a {@link #moveTo} or {@link #hopTo} has not arrived yet. */
    boolean isMoving();

    /**
     * Switches the player's use of the node off or on: while off it stays visible, but the cursor passes through it and it
     * cannot be taken, clicked or turned. Pieces that are not the player's to move, a control out of order.
     */
    void setEnabled(boolean enabled);

    boolean isEnabled();

    /**
     * Takes the hover outline over for the player working at the station: from the first call on code alone decides, the
     * cursor no longer does. The piece they picked stays outlined, a piece they cannot act on stays without one.
     */
    void setOutlined(boolean outlined);

    /** Turns the node by extra Euler degrees (applied y, x, z) over {@code seconds}, eased. */
    void rotateTo(float x, float y, float z, float seconds);

    /** Scales the node by {@code factor} over {@code seconds}, eased. */
    void scaleTo(float factor, float seconds);

    /**
     * Keeps the node turning about its own axes at {@code x, y, z} degrees per second (a stirrer bar, a fan), on top of
     * {@link #rotateTo}; all zeros stops it where it is.
     */
    void spin(float x, float y, float z);

    /** Shakes the node by up to {@code pixels} in every direction, dying away over {@code seconds} (a bump, a burst). */
    void shake(float pixels, float seconds);

    /**
     * Draws another visual instead of the scene file's, in the visual's own tint; {@code null} goes back to it. The hit box
     * stays the scene's.
     */
    void setVisual(Visual visual);

    /** The visual drawn now: the one set by code, else the scene file's. */
    java.util.Optional<Visual> visual();

    /**
     * Changes the text shown next to the cursor over the node ({@code null}: the scene file's {@code tooltip} again). A
     * gauge reading, the name of what a sample tube holds.
     */
    void setTooltip(Component text);

    /** Multiplies an ARGB colour into the node's visual (alpha below 255 fades it). */
    void setTint(int argb);

    /** Switches the node's particle emitter (from the scene file) on or off. */
    void setEmitting(boolean emitting);

    /**
     * Changes how many particles a second the node's emitter gives off (the scene file's {@code rate} until then): a
     * flame that grows with the gas knob.
     */
    void setEmitRate(float perSecond);

    /**
     * Draws the node's liquid in another colour whatever it is made of, an ARGB value; 0 goes back to the liquid's own. The
     * colour drifts there as a mixture's does: a soup that takes the colour of what is in it.
     */
    void setLiquidColor(int argb);

    /**
     * How restless the node's liquid surface is, 0 (still) to 1 (a rolling boil); the surface churns and swells with it.
     */
    void setAgitation(float amount);

    /**
     * Leaves a trail on the node's liquid at {@code x, z} (the node's pixels): call it as something moves through, a spoon, a finger. The trail is
     * a furrow in the liquid's own colour, lit on one side; it stays in the liquid, goes round with {@link #setCurrent}, runs out and fades as
     * {@link #setWakeLook} says. Square tanks seen from above.
     */
    void leaveWake(float x, float z);

    /**
     * The same for something of its own size: {@code width} is how wide the trail starts, in the node's pixels, {@code strength} how deep it
     * is, 0 to 1.
     */
    void leaveWake(float x, float z, float width, float strength);

    /**
     * How long trails stay on the node's liquid, in seconds, and how clearly they stand out, 0 to 1 (by default 2.5 and 0.6): thin water forgets a
     * stir at once, a thick soup keeps it. Call it as the liquid changes.
     */
    void setWakeLook(float seconds, float contrast);

    /** How clear the trails of what floats on the node's liquid are, 0 to 1: pieces leave them by themselves as they move fast enough. 0 leaves none. */
    void setFloatingWake(float strength);

    /**
     * How hard the node's liquid seethes along the walls, 0 to 1, where a pot starts to boil: specks of foam come and go on the surface
     * next to them. Square tanks seen from above. {@code StationView.spawnAtLiquidEdge} adds the small bubbles.
     */
    void setEdgeBoil(float amount);

    /**
     * How uneven the node's liquid looks, 0 (one colour) to 1: lighter and darker patches on the surface, pixel by pixel, that go round
     * with {@link #setCurrent}. A soup with fat on it. Works on square tanks seen from above; it drifts there.
     */
    void setMottle(float amount);

    /**
     * Sends ripples over the node's liquid from a point, in the node's pixels: something dropped in, a stirring rod.
     * {@code strength} is about the wave's height in pixels; nothing happens without a tank.
     */
    void ripple(float x, float z, float strength);

    /**
     * Floats {@code count} things on the node's liquid, taken in turns from {@code floaters}. They come and go one by one, so a soup can
     * fill up as it cooks and thin out as it is served; things no longer in the list leave. Nothing happens without a tank; an empty
     * list clears it. {@link #setCurrent} carries them round.
     */
    void setFloating(java.util.List<Floater> floaters, int count);

    /**
     * What stands in the node's liquid: x, z and radius of each, one after the other, in the node's pixels. What floats is pushed aside and
     * goes round them, as round a spoon. Call it every frame while they move; nothing takes them away. Floaters push each other by themselves.
     */
    void setFloatingObstacles(float... xzRadius);

    /** How fast the node's liquid turns, radians per second, the sign saying which way round: it carries what floats on it. */
    void setCurrent(float radiansPerSecond);

    /**
     * Draws a shape made by code at the node, in the node's pixels, with {@code texture} (a sprite of the block atlas); null takes
     * it away. It goes with the node's visual, if there is one. Call it again when the shape changes. A tint with an alpha below
     * 255 ({@link #setTint}) makes it see-through.
     */
    void setShape(MeshShape shape, net.minecraft.resources.Identifier texture);

    /** Draws by code at the node every frame, with the scene: a vanilla model, text on a page; {@code null} takes it away. */
    void setPainter(NodePainter painter);

    /** {@link #setShape}; with {@code glowing} it is drawn at full brightness, for marks that must read in the dark as well. */
    void setShape(MeshShape shape, net.minecraft.resources.Identifier texture, boolean glowing);

    /** Changes the light the node gives (0-15); see the scene's {@code light}. */
    void setLight(int level);

    /** Where the node's origin is in the world right now. */
    Vec3 worldPosition();

    /**
     * The node's matrix in scene space (pixels, before the facing turn) as drawn this frame: also while it is carried or
     * tipped. Points in the node's own pixels go through it to the scene, for {@code StationView.spawnParticles}.
     */
    Matrix4fc sceneMatrix();

    /** Where the node's origin is in scene space right now (pixels, before the facing turn). */
    default Vector3f scenePosition() {
        return sceneMatrix().transformPosition(new Vector3f());
    }

    /** Whether the cursor is over the node. */
    boolean isHovered();

    /** Whether the local player is carrying the node. */
    boolean isHeld();
}
