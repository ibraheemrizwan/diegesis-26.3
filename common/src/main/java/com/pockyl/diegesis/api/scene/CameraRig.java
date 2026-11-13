package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the camera goes while a player works at the block. Coordinates are Blockbench pixels relative to the block's
 * corner, for a block facing north (its front on the -Z side, as Blockbench shows a block model); the library turns
 * them with the block.
 *
 * @param position   the camera's position
 * @param target     the point the camera looks at
 * @param fov        vertical field of view in degrees while focused; 0 keeps the player's setting
 * @param transition seconds the camera takes to fly in and out
 * @param inventory  a second shot the camera glides to while the scene's inventory is shown (a {@code toggle} layout
 *                   opened with Tab, or an item on the cursor): frame the inventory and the slots it fills together,
 *                   and back when it hides. Without it the camera stays put
 * @param glances    other places the player's look can go to: the cursor at an edge of the screen, or code, glides the
 *                   camera there and back
 * @param seats      the view from each seat of the station ({@code StationType.Builder.seat}), by seat id: a player in a
 *                   seat sees that shot instead of {@code position} and {@code target}. Who has no seat, or a seat without
 *                   a shot, sees the rig's own
 * @param neighbors  going over to a station standing next to this one, by the cursor at an edge of the screen
 */
public record CameraRig(Vector3fc position, Vector3fc target, float fov, float transition, Optional<CameraShot> inventory,
        List<CameraGlance> glances, Map<String, CameraShot> seats, CameraNeighbors neighbors) {
    /** Looks down at the top of the block from the front, like a person standing at a table. */
    public static final CameraRig DEFAULT = new CameraRig(new Vector3f(8.0F, 36.0F, -12.0F), new Vector3f(8.0F, 16.0F, 7.0F), 0.0F, 0.6F,
            Optional.empty(), List.of(), Map.of(), CameraNeighbors.DEFAULT);

    public static final Codec<CameraRig> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(StrictCodecs.VEC3, "position", new Vector3f(DEFAULT.position)).forGetter(r -> new Vector3f(r.position)),
            StrictCodecs.optional(StrictCodecs.VEC3, "target", new Vector3f(DEFAULT.target)).forGetter(r -> new Vector3f(r.target)),
            StrictCodecs.optional(Codec.floatRange(0.0F, 170.0F), "fov", DEFAULT.fov).forGetter(CameraRig::fov),
            StrictCodecs.optional(Codec.floatRange(0.0F, 10.0F), "transition", DEFAULT.transition).forGetter(CameraRig::transition),
            StrictCodecs.optional(CameraShot.CODEC, "inventory").forGetter(CameraRig::inventory),
            StrictCodecs.optional(CameraGlance.CODEC.listOf(), "glances", List.of()).forGetter(CameraRig::glances),
            StrictCodecs.optional(Codec.unboundedMap(Codec.STRING, CameraShot.CODEC), "seats", Map.of()).forGetter(CameraRig::seats),
            StrictCodecs.optional(CameraNeighbors.CODEC, "neighbors", CameraNeighbors.DEFAULT).forGetter(CameraRig::neighbors)
    ).apply(instance, CameraRig::new));

    public CameraRig {
        position = new Vector3f(position);
        target = new Vector3f(target);
        glances = List.copyOf(glances);
        seats = Map.copyOf(seats);
    }

    /** A rig with the default {@link CameraNeighbors}. */
    public CameraRig(Vector3fc position, Vector3fc target, float fov, float transition, Optional<CameraShot> inventory,
            List<CameraGlance> glances, Map<String, CameraShot> seats) {
        this(position, target, fov, transition, inventory, glances, seats, CameraNeighbors.DEFAULT);
    }

    /** A camera at {@code position} looking at {@code target}, with the player's field of view. */
    public static CameraRig of(Vector3fc position, Vector3fc target) {
        return new CameraRig(position, target, 0.0F, DEFAULT.transition, Optional.empty(), List.of(), Map.of(), CameraNeighbors.DEFAULT);
    }

    /** The same rig gliding to {@code shot} while the inventory is shown. */
    public CameraRig withInventoryShot(CameraShot shot) {
        return new CameraRig(position, target, fov, transition, Optional.of(shot), glances, seats, neighbors);
    }

    /** The same rig with one more place to look at. */
    public CameraRig withGlance(CameraGlance glance) {
        List<CameraGlance> all = new ArrayList<>(glances);
        all.add(glance);
        return new CameraRig(position, target, fov, transition, inventory, all, seats, neighbors);
    }

    /** The same rig with the view from one more seat. */
    public CameraRig withSeat(String seat, CameraShot shot) {
        Map<String, CameraShot> all = new java.util.HashMap<>(seats);
        all.put(seat, shot);
        return new CameraRig(position, target, fov, transition, inventory, glances, all, neighbors);
    }

    /** The same rig with other rules for going over to neighbouring stations ({@link CameraNeighbors#NONE}: never). */
    public CameraRig withNeighbors(CameraNeighbors value) {
        return new CameraRig(position, target, fov, transition, inventory, glances, seats, value);
    }

    /** The glance with this id. */
    public Optional<CameraGlance> glance(String id) {
        return glances.stream().filter(glance -> glance.id().equals(id)).findFirst();
    }
}
