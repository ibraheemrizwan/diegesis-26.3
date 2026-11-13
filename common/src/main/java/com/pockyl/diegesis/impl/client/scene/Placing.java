package com.pockyl.diegesis.impl.client.scene;

import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.scene.PlaceSurface;
import com.pockyl.diegesis.api.state.ItemPose;

/** Where items placed freely can stand: the geometry of the surfaces of a scene's {@code "placing"}. */
public final class Placing {
    /** How far (pixels) past its edges a surface still catches the cursor. */
    private static final float AIM_MARGIN = 1.5F;
    /** Hit this high on an item (0 its foot, 1 its top), the item in the hand goes on top of it instead of in front. */
    private static final float ON_TOP_FROM = 0.6F;
    /** An item this low (pixels) is all top: a book lying down. */
    private static final float LOW_ITEM = 2.5F;

    private Placing() {
    }

    /** A point on a surface the cursor is aimed at. */
    public record Aim(PlaceSurface surface, float x, float z) {
    }

    /** The surface the cursor ray finds first, or null. */
    public static Aim aim(PlacingOptions options, Vector3fc origin, Vector3fc direction) {
        Aim best = null;
        float nearest = Float.MAX_VALUE;
        for (PlaceSurface surface : options.surfaces()) {
            float depth = Math.max(0.01F, surface.to().y() - surface.from().y());
            float slope = surface.aimHeight() / depth;
            // The slope: y = height + slope * (z - front).
            float denominator = direction.y() - slope * direction.z();
            if (Math.abs(denominator) < 1.0e-6F) {
                continue;
            }
            float t = (surface.height() + slope * (origin.z() - surface.from().y()) - origin.y()) / denominator;
            float x = origin.x() + direction.x() * t;
            float z = origin.z() + direction.z() * t;
            if (t <= 0.0F || t >= nearest || x < surface.from().x() - AIM_MARGIN || x > surface.to().x() + AIM_MARGIN
                    || z < surface.from().y() - AIM_MARGIN || z > surface.to().y() + AIM_MARGIN) {
                continue;
            }
            nearest = t;
            best = new Aim(surface, x, z);
        }
        return best;
    }

    /** Whether a point hit on {@code below} means on top of it. */
    public static boolean isOnTop(ItemPose below, float hitY) {
        return below.lift() * 2.0F <= LOW_ITEM || hitY >= below.y() + below.lift() * 2.0F * ON_TOP_FROM;
    }

    /** The surface an item standing at this pose belongs to, or null. */
    public static PlaceSurface surfaceOf(PlacingOptions options, ItemPose pose) {
        for (PlaceSurface surface : options.surfaces()) {
            if (surface.holds(pose.x(), pose.y(), pose.z())) {
                return surface;
            }
        }
        return null;
    }

    /** {@code item_size * scale} of an item, cut down so that it fits under what is above it. */
    public static float fittingScale(PlacingOptions options, PlacedShape shape, Quaternionfc rotation, float scale, float room) {
        float height = shape.extents(rotation, options.itemSize() * scale).y * 2.0F;
        return height <= room ? scale : scale * room / height;
    }

    /** An item standing on {@code surface} at {@code x, z}, kept within its edges; at {@code y} over what it stands on. */
    public static ItemPose standing(PlacingOptions options, PlacedShape shape, PlaceSurface surface, float x, float y, float z, Quaternionfc rotation,
            float wantedScale, int on) {
        float room = surface.height() + surface.clearance() - y;
        float scale = fittingScale(options, shape, rotation, wantedScale, room);
        Vector3f extents = shape.extents(rotation, options.itemSize() * scale);
        float keptX = keepWithin(snapped(options, x), surface.from().x(), surface.to().x(), extents.x);
        float keptZ = keepWithin(snapped(options, z), surface.from().y(), surface.to().y(), extents.z);
        return new ItemPose(keptX, y, keptZ, extents.y, rotation, scale, on);
    }

    private static float keepWithin(float value, float low, float high, float half) {
        return low + half > high - half ? (low + high) / 2.0F : Mth.clamp(value, low + half, high - half);
    }

    private static float snapped(PlacingOptions options, float value) {
        return options.snap() <= 0.0F ? value : Math.round(value / options.snap()) * options.snap();
    }

    /** A place for an item nobody put down by hand: in rows along the surfaces, by its slot's number. */
    static ItemPose fallback(PlacingOptions options, int index, PlacedShape shape) {
        PlaceSurface surface = options.surfaces().get(index % options.surfaces().size());
        int place = index / options.surfaces().size();
        float width = surface.to().x() - surface.from().x();
        int perRow = Math.max(1, (int) (width / options.itemSize()));
        float x = surface.from().x() + (place % perRow + 0.5F) * width / perRow;
        float z = (surface.from().y() + surface.to().y()) / 2.0F;
        return standing(options, shape, surface, x, surface.height(), z, new Quaternionf(), 1.0F, -1);
    }
}
