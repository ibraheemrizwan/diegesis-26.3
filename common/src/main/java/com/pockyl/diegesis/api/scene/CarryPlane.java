package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.util.Mth;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.List;
import java.util.Optional;

/**
 * Shapes where carried objects go over part of the scene. With a {@code height}, a level they rise (or sink) to: brought
 * towards a big flask, a vial goes up over its neck instead of sliding along the table into it; outside the area the
 * object stays at its normal height (the work plane plus its {@code lift}), within {@code blend} pixels of it it climbs
 * smoothly, and where planes overlap the highest wins. With {@code "closed": true}, the area is shut: objects stop
 * {@code blend} pixels short of it (a torch stays outside the burner it lights). Written as a list,
 * {@code "carry_planes"}, in a scene file.
 *
 * @param from   one corner of the area, {@code [x, z]} in scene pixels
 * @param to     the opposite corner
 * @param height the height (scene pixels) of the object's grip point over the area; none for a closed area
 * @param blend  how many pixels round the area the object takes to climb to it, or stays away from a closed one
 * @param closed objects may not enter the area
 * @param nodes  the grabbable nodes it applies to; all of them when empty
 */
public record CarryPlane(Vector2fc from, Vector2fc to, Optional<Float> height, float blend, boolean closed, List<String> nodes) {
    private static final Codec<Vector2fc> XZ = Codec.FLOAT.listOf().comapFlatMap(
            list -> list.size() == 2 ? DataResult.success((Vector2fc) new Vector2f(list.get(0), list.get(1)))
                    : DataResult.error(() -> "Expected [x, z], got " + list),
            vector -> List.of(vector.x(), vector.y()));

    public static final Codec<CarryPlane> CODEC = StrictCodecs.record(instance -> instance.group(
            XZ.fieldOf("from").forGetter(CarryPlane::from),
            XZ.fieldOf("to").forGetter(CarryPlane::to),
            StrictCodecs.optional(Codec.FLOAT, "height").forGetter(CarryPlane::height),
            StrictCodecs.optional(Codec.floatRange(0.0F, 64.0F), "blend", 4.0F).forGetter(CarryPlane::blend),
            StrictCodecs.optional(Codec.BOOL, "closed", false).forGetter(CarryPlane::closed),
            StrictCodecs.optional(NodeDefinition.ID_CODEC.listOf(), "nodes", List.of()).forGetter(CarryPlane::nodes)
    ).apply(instance, CarryPlane::new));

    public CarryPlane {
        Vector2f low = new Vector2f(Math.min(from.x(), to.x()), Math.min(from.y(), to.y()));
        Vector2f high = new Vector2f(Math.max(from.x(), to.x()), Math.max(from.y(), to.y()));
        from = low;
        to = high;
        nodes = List.copyOf(nodes);
    }

    /** Whether the plane lifts the given grabbable node. */
    public boolean appliesTo(String node) {
        return nodes.isEmpty() || nodes.contains(node);
    }

    /**
     * The height at {@code x, z} for an object normally carried at {@code base}: {@code height} over the area, eased
     * back to {@code base} over the blend distance round it.
     */
    public float heightAt(float x, float z, float base) {
        if (height.isEmpty()) {
            return base;
        }
        float dx = Math.max(0.0F, Math.max(from.x() - x, x - to.x()));
        float dz = Math.max(0.0F, Math.max(from.y() - z, z - to.y()));
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance >= blend) {
            return base;
        }
        float t = blend <= 0.0F ? 1.0F : 1.0F - distance / blend;
        t = t * t * (3.0F - 2.0F * t);
        return Mth.lerp(t, base, height.get());
    }

    /** For a closed area: moves {@code point} (x, z) out to {@code blend} pixels round the area if it is inside that. */
    public void keepOut(org.joml.Vector3f point) {
        if (!closed) {
            return;
        }
        float minX = from.x() - blend;
        float maxX = to.x() + blend;
        float minZ = from.y() - blend;
        float maxZ = to.y() + blend;
        if (point.x <= minX || point.x >= maxX || point.z <= minZ || point.z >= maxZ) {
            return;
        }
        // Out through the nearest side.
        float left = point.x - minX;
        float right = maxX - point.x;
        float near = point.z - minZ;
        float far = maxZ - point.z;
        float least = Math.min(Math.min(left, right), Math.min(near, far));
        if (least == left) {
            point.x = minX;
        } else if (least == right) {
            point.x = maxX;
        } else if (least == near) {
            point.z = minZ;
        } else {
            point.z = maxZ;
        }
    }
}
