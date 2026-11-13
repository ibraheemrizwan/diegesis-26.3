package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.List;
import java.util.Optional;

/**
 * A level surface items can stand on.
 *
 * @param from      one corner, {@code [x, z]} in scene pixels
 * @param to        the opposite corner
 * @param height    its height in scene pixels
 * @param clearance how much room there is above it, in pixels: nothing that stands here grows through what is over it
 * @param aimHeight how the cursor finds a place. 0: it points at the surface itself, right for a table seen from above.
 *                  A shelf seen from the front is too thin a sliver for that: with a height, the cursor points at a slope
 *                  from the front edge (the side of the smaller z) up to this height over the back edge, so that low in
 *                  the opening means at the front and high in the opening means at the back
 * @param wall      the z of a wall behind the surface: light in the room falls on it too
 */
public record PlaceSurface(Vector2fc from, Vector2fc to, float height, float clearance, float aimHeight, Optional<Float> wall) {
    private static final Codec<Vector2fc> XZ = Codec.FLOAT.listOf().comapFlatMap(
            list -> list.size() == 2 ? DataResult.success((Vector2fc) new Vector2f(list.get(0), list.get(1)))
                    : DataResult.error(() -> "Expected [x, z], got " + list),
            vector -> List.of(vector.x(), vector.y()));

    public static final Codec<PlaceSurface> CODEC = StrictCodecs.record(instance -> instance.group(
            XZ.fieldOf("from").forGetter(PlaceSurface::from),
            XZ.fieldOf("to").forGetter(PlaceSurface::to),
            Codec.FLOAT.fieldOf("height").forGetter(PlaceSurface::height),
            StrictCodecs.optional(Codec.floatRange(0.5F, 256.0F), "clearance", 64.0F).forGetter(PlaceSurface::clearance),
            StrictCodecs.optional(Codec.floatRange(0.0F, 256.0F), "aim_height", 0.0F).forGetter(PlaceSurface::aimHeight),
            StrictCodecs.optional(Codec.FLOAT, "wall").forGetter(PlaceSurface::wall)
    ).apply(instance, PlaceSurface::new));

    public PlaceSurface {
        Vector2f low = new Vector2f(Math.min(from.x(), to.x()), Math.min(from.y(), to.y()));
        Vector2f high = new Vector2f(Math.max(from.x(), to.x()), Math.max(from.y(), to.y()));
        from = low;
        to = high;
    }

    /** Whether {@code x, z} lies on the surface and the height {@code y} in the room above it. */
    public boolean holds(float x, float y, float z) {
        return x >= from.x() - 0.01F && x <= to.x() + 0.01F && z >= from.y() - 0.01F && z <= to.y() + 0.01F
                && y >= height - 0.01F && y <= height + clearance;
    }
}
