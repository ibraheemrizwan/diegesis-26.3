package com.pockyl.diegesis.impl.client.focus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.pockyl.diegesis.api.scene.CameraGlance;
import com.pockyl.diegesis.api.scene.CameraNeighbors;
import com.pockyl.diegesis.api.scene.CameraRig;
import com.pockyl.diegesis.api.scene.InventoryDrawer.Side;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;
import com.pockyl.diegesis.impl.client.scene.SceneLibrary;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The stations standing next to the one worked at, by the edge of the screen on their side ({@link CameraNeighbors}), and the
 * cursor resting at such an edge. Like a glance's strip, an edge the cursor is already at when a station opens does nothing
 * until the cursor has left it: arriving with the cursor at the edge never flies on by itself.
 */
final class Neighbors {
    /** Seconds between two looks around: blocks placed or broken meanwhile are found that soon. */
    private static final float RESCAN = 0.25F;

    record Neighbor(Side edge, BlockPos pos, StationType type, CameraNeighbors rules) {
    }

    private final Map<Side, Neighbor> found = new EnumMap<>(Side.class);
    private final Set<Side> spent = EnumSet.noneOf(Side.class);
    private BlockPos scannedFrom;
    private float scanWait;
    private Side restingAt;
    private float resting;
    private boolean fresh = true;

    /** For a new station: look around at once, and the edge under the cursor waits until the cursor leaves it. */
    void reset() {
        found.clear();
        spent.clear();
        scannedFrom = null;
        restingAt = null;
        resting = 0.0F;
        fresh = true;
    }

    /**
     * Follows the cursor; once per frame while the player can interact. Returns the station to go over to once the cursor has
     * rested at its edge long enough, else null.
     *
     * @param free nothing keeps the camera here: no stack on the cursor, no object in the hand, no glance away
     */
    Neighbor watch(ClientStation station, float u, float v, float dt, boolean free) {
        CameraNeighbors rules = rules(station.scene().definition());
        if (!rules.enabled()) {
            found.clear();
            return null;
        }
        scanWait -= dt;
        if (scanWait <= 0.0F || !station.pos().equals(scannedFrom)) {
            scan(station, rules);
            scanWait = RESCAN;
        }
        if (fresh) {
            fresh = false;
            for (Side edge : Side.values()) {
                if (Glance.distance(edge, u, v) <= rules.zone()) {
                    spent.add(edge);
                }
            }
        }
        spent.removeIf(edge -> Glance.distance(edge, u, v) > rules.zone());
        Side at = null;
        for (Side edge : found.keySet()) {
            if (!spent.contains(edge) && Glance.distance(edge, u, v) <= rules.zone()) {
                at = edge;
                break;
            }
        }
        if (at == null || !free) {
            restingAt = null;
            resting = 0.0F;
            return null;
        }
        if (at != restingAt) {
            restingAt = at;
            resting = 0.0F;
        }
        resting += dt;
        if (resting < rules.delay()) {
            return null;
        }
        resting = 0.0F;
        spent.add(at);
        return found.get(at);
    }

    /** The neighbour of {@code station} on {@code edge}; looks around first when the last look was from another station. */
    Neighbor towards(ClientStation station, Side edge) {
        CameraNeighbors rules = rules(station.scene().definition());
        if (!rules.enabled()) {
            return null;
        }
        if (!station.pos().equals(scannedFrom)) {
            scan(station, rules);
            scanWait = RESCAN;
        }
        return found.get(edge);
    }

    /** The neighbours by edge, as last found. */
    Map<Side, Neighbor> found() {
        return found;
    }

    static CameraNeighbors rules(SceneDefinition scene) {
        return scene.camera().neighbors();
    }

    /**
     * Every station around that the player could open from where they stand and that allows it, nearest first on each edge: the
     * edge is the side of the screen the station is on, seen from the rig. Edges a glance or the drawer uses are theirs.
     */
    private void scan(ClientStation station, CameraNeighbors rules) {
        found.clear();
        scannedFrom = station.pos();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Level level = player.level();
        SceneDefinition scene = station.scene().definition();
        CameraRig rig = scene.camera();
        Set<Side> taken = EnumSet.noneOf(Side.class);
        for (CameraGlance glance : rig.glances()) {
            glance.edge().ifPresent(taken::add);
        }
        scene.inventoryDrawer().ifPresent(drawer -> taken.add(drawer.side()));
        Vec3 eye = station.toWorld(rig.position().x(), rig.position().y(), rig.position().z());
        Vec3 look = station.toWorld(rig.target().x(), rig.target().y(), rig.target().z()).subtract(eye);
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        if (forward.lengthSqr() < 1.0e-6) {
            return;
        }
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        Vec3 here = Vec3.atCenterOf(station.pos());
        Map<Side, Double> nearest = new EnumMap<>(Side.class);
        int range = rules.range();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    at.set(station.pos().getX() + dx, station.pos().getY() + dy, station.pos().getZ() + dz);
                    BlockState state = level.getBlockState(at);
                    StationType type = StationRegistry.find(state).orElse(null);
                    if (type == null) {
                        continue;
                    }
                    BlockPos pos = at.immutable();
                    SceneDefinition theirs = SceneLibrary.INSTANCE.scene(type.scene());
                    if (theirs == null || !rules(theirs).enabled() || !type.anchor().resolve(level, pos, state).equals(pos)
                            || pos.equals(station.pos()) || !DiegesisClientRuntime.mayOpen(player, type, pos, state)) {
                        continue;
                    }
                    Vec3 away = Vec3.atCenterOf(pos).subtract(here);
                    double across = away.dot(right);
                    double along = away.dot(forward);
                    Side edge = Math.abs(across) >= Math.abs(along) ? across > 0.0 ? Side.RIGHT : Side.LEFT
                            : along > 0.0 ? Side.TOP : Side.BOTTOM;
                    double distance = away.lengthSqr();
                    if (taken.contains(edge) || nearest.containsKey(edge) && nearest.get(edge) <= distance) {
                        continue;
                    }
                    nearest.put(edge, distance);
                    found.put(edge, new Neighbor(edge, pos, type, rules));
                }
            }
        }
    }
}
