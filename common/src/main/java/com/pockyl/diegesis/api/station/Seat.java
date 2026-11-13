package com.pockyl.diegesis.api.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A place at a station, for stations where it matters who is where: the two sides of a game board, the posts of a crew.
 * Every seat holds one player and has a view of its own ({@code camera.seats} in the scene).
 *
 * @param id       the seat's name, also the key of its shot in {@code camera.seats}
 * @param position where the seat is, in scene pixels (block facing north)
 * @param sit      whether the player sits down there while working, for everyone to see; {@code position} is then the
 *                 surface they sit on
 */
public record Seat(String id, Vector3fc position, boolean sit) {
    public Seat {
        position = new Vector3f(position);
    }

    /** A place to stand at. */
    public static Seat at(String id, float x, float y, float z) {
        return new Seat(id, new Vector3f(x, y, z), false);
    }

    /** A place to sit at: {@code x, y, z} is the surface the player sits on. */
    public static Seat sitting(String id, float x, float y, float z) {
        return new Seat(id, new Vector3f(x, y, z), true);
    }

    /** Where the seat is in the world for a station at {@code pos} facing {@code facing}. */
    public Vec3 worldPosition(BlockPos pos, Direction facing) {
        float x = position.x() / 16.0F - 0.5F;
        float z = position.z() / 16.0F - 0.5F;
        float turnedX = switch (facing) {
            case EAST -> -z;
            case SOUTH -> -x;
            case WEST -> z;
            default -> x;
        };
        float turnedZ = switch (facing) {
            case EAST -> x;
            case SOUTH -> -z;
            case WEST -> -x;
            default -> z;
        };
        return new Vec3(pos.getX() + 0.5 + turnedX, pos.getY() + position.y() / 16.0F, pos.getZ() + 0.5 + turnedZ);
    }
}
