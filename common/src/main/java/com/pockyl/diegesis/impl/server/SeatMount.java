package com.pockyl.diegesis.impl.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * What a player sits on at a station's seat: an empty display entity (nothing to see, no hit box, no gravity), never saved
 * with the world, so a crash leaves nothing behind.
 */
final class SeatMount {
    /** A rider's hips are this far above their mount, in blocks. */
    private static final double HIPS_OVER_MOUNT = 0.4;

    private final Display.ItemDisplay entity;
    private final Vec3 surface;

    private SeatMount(Display.ItemDisplay entity, Vec3 surface) {
        this.entity = entity;
        this.surface = surface;
    }

    /** Sits {@code player} down on {@code surface}; null when the level refuses the entity. */
    static SeatMount sit(ServerLevel level, ServerPlayer player, Vec3 surface) {
        Display.ItemDisplay entity = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        entity.setPos(surface.x, surface.y - HIPS_OVER_MOUNT, surface.z);
        if (!level.addFreshEntity(entity)) {
            return null;
        }
        player.stopRiding();
        if (!player.startRiding(entity, true, true)) {
            entity.discard();
            return null;
        }
        return new SeatMount(entity, surface);
    }

    /** Stands the player up on the seat. */
    void leave(ServerPlayer player) {
        if (player.getVehicle() == entity) {
            player.stopRiding();
            if (player.isAlive() && !player.hasDisconnected()) {
                player.teleportTo(surface.x, surface.y, surface.z);
            }
        }
        entity.discard();
    }
}
