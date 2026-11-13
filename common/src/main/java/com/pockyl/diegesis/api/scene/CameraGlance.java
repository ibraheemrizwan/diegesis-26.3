package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.InventoryDrawer.Side;
import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A place the player's look can go to while working at a station ({@link CameraRig#glances()}): the rig shows the table
 * top, and the cursor resting at the bottom of the screen glides the camera down to the inventory; taking a stack there
 * glides it back. Coordinates are those of the rig.
 *
 * <p>The cursor stays free the whole time: once the camera is there the player moves it over what the glance shows, and
 * nothing happens until the cursor reaches the opposite edge or a stack is taken. A cursor still at the edge after coming
 * back has to leave it first, and an object in the hand or a control being turned never moves the camera.
 *
 * @param id         unique in the scene, {@code [a-z0-9_]+}; {@code StationView.glance} and {@code SceneEvent.Glance} use it
 * @param edge       the edge of the screen the cursor rests at to look here; without one only code does
 * @param zone       how far from that edge the cursor counts as being at it, as a part of the screen (0.1 = a tenth)
 * @param delay      seconds the cursor must rest there, so that passing by moves nothing
 * @param position   the camera's position
 * @param target     the point it looks at
 * @param fov        field of view in degrees; 0 keeps the rig's
 * @param time       seconds the camera takes to get there and back
 * @param backOnTake look back as soon as a stack comes onto the cursor here
 * @param backZone   the same kind of strip along the opposite edge, which looks back; 0: no strip, only a taken stack or
 *                   code ends the glance
 * @param hint       draw a small arrow at the edge the look can go to (or come back from)
 * @param hintColor  ARGB colour of the arrow; its alpha is the strongest it gets, with the cursor at the edge
 */
public record CameraGlance(String id, Optional<Side> edge, float zone, float delay, Vector3fc position, Vector3fc target, float fov,
        float time, boolean backOnTake, float backZone, boolean hint, int hintColor) {
    public static final float DEFAULT_ZONE = 0.1F;
    public static final float DEFAULT_DELAY = 0.12F;
    public static final float DEFAULT_TIME = 0.45F;
    public static final float DEFAULT_BACK_ZONE = 0.12F;
    public static final int DEFAULT_HINT_COLOR = 0xD0FFFFFF;

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    public static final Codec<CameraGlance> CODEC = StrictCodecs.record(instance -> instance.group(
            Codec.STRING.comapFlatMap(CameraGlance::checkId, id -> id).fieldOf("id").forGetter(CameraGlance::id),
            StrictCodecs.optional(Side.CODEC, "edge").forGetter(CameraGlance::edge),
            StrictCodecs.optional(Codec.floatRange(0.01F, 0.5F), "zone", DEFAULT_ZONE).forGetter(CameraGlance::zone),
            StrictCodecs.optional(Codec.floatRange(0.0F, 2.0F), "delay", DEFAULT_DELAY).forGetter(CameraGlance::delay),
            StrictCodecs.VEC3.fieldOf("position").forGetter(glance -> new Vector3f(glance.position)),
            StrictCodecs.VEC3.fieldOf("target").forGetter(glance -> new Vector3f(glance.target)),
            StrictCodecs.optional(Codec.floatRange(0.0F, 170.0F), "fov", 0.0F).forGetter(CameraGlance::fov),
            StrictCodecs.optional(Codec.floatRange(0.0F, 10.0F), "time", DEFAULT_TIME).forGetter(CameraGlance::time),
            StrictCodecs.optional(Codec.BOOL, "back_on_take", true).forGetter(CameraGlance::backOnTake),
            StrictCodecs.optional(Codec.floatRange(0.0F, 0.5F), "back_zone", DEFAULT_BACK_ZONE).forGetter(CameraGlance::backZone),
            StrictCodecs.optional(Codec.BOOL, "hint", true).forGetter(CameraGlance::hint),
            StrictCodecs.optional(Visual.COLOR, "hint_color", DEFAULT_HINT_COLOR).forGetter(CameraGlance::hintColor)
    ).apply(instance, CameraGlance::new));

    public CameraGlance {
        position = new Vector3f(position);
        target = new Vector3f(target);
    }

    private static DataResult<String> checkId(String id) {
        return ID.matcher(id).matches() ? DataResult.success(id) : DataResult.error(() -> "A glance id is [a-z0-9_]+: " + id);
    }

    /** A glance from {@code position} at {@code target}, started by the cursor resting at {@code edge}. */
    public static CameraGlance at(String id, Side edge, Vector3fc position, Vector3fc target) {
        return new CameraGlance(id, Optional.of(edge), DEFAULT_ZONE, DEFAULT_DELAY, position, target, 0.0F, DEFAULT_TIME, true,
                DEFAULT_BACK_ZONE, true, DEFAULT_HINT_COLOR);
    }

    /** A glance only code starts ({@code StationView.glance}). */
    public static CameraGlance byCode(String id, Vector3fc position, Vector3fc target) {
        return new CameraGlance(id, Optional.empty(), DEFAULT_ZONE, DEFAULT_DELAY, position, target, 0.0F, DEFAULT_TIME, true, 0.0F, false,
                DEFAULT_HINT_COLOR);
    }

    public CameraGlance withFov(float value) {
        return new CameraGlance(id, edge, zone, delay, position, target, value, time, backOnTake, backZone, hint, hintColor);
    }

    public CameraGlance withTime(float value) {
        return new CameraGlance(id, edge, zone, delay, position, target, fov, value, backOnTake, backZone, hint, hintColor);
    }

    /** The same glance with other strips and rest time for the cursor. */
    public CameraGlance withZones(float value, float back, float rest) {
        return new CameraGlance(id, edge, value, rest, position, target, fov, time, backOnTake, back, hint, hintColor);
    }

    public CameraGlance withBackOnTake(boolean value) {
        return new CameraGlance(id, edge, zone, delay, position, target, fov, time, value, backZone, hint, hintColor);
    }

    public CameraGlance withHint(boolean value, int color) {
        return new CameraGlance(id, edge, zone, delay, position, target, fov, time, backOnTake, backZone, value, color);
    }
}
