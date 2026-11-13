package com.pockyl.diegesis.api.client;

import org.jetbrains.annotations.ApiStatus;

import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.impl.client.mesh.Mesh;

import java.util.Arrays;

/**
 * Polygons made by code, in pixels: a shape the player changes while it is on screen ({@link NodeView#setShape}), or the look of
 * an item that depends on its data ({@link DiegesisClient#registerItemShape}). Build a new one when the shape changes; a few
 * thousand corners per frame are no problem.
 */
public final class MeshShape {
    private final Mesh mesh;

    private MeshShape(Mesh mesh) {
        this.mesh = mesh;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The box round the shape, in its own pixels. */
    public Box bounds() {
        return mesh.bounds();
    }

    @ApiStatus.Internal
    public Mesh mesh() {
        return mesh;
    }

    /** Collects faces of four corners, counter-clockwise seen from outside (a triangle repeats its last corner). */
    public static final class Builder {
        private float[] vertices = new float[Mesh.STRIDE * 4 * 64];
        private int size;

        private Builder() {
        }

        /**
         * One corner of a face.
         *
         * @param u 0..1 over the whole texture
         * @param v 0..1 over the whole texture, downwards
         */
        public Builder corner(float x, float y, float z, float u, float v, float normalX, float normalY, float normalZ) {
            if (size + Mesh.STRIDE > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            vertices[size++] = x;
            vertices[size++] = y;
            vertices[size++] = z;
            vertices[size++] = u;
            vertices[size++] = v;
            vertices[size++] = normalX;
            vertices[size++] = normalY;
            vertices[size++] = normalZ;
            return this;
        }

        public MeshShape build() {
            if (size % (Mesh.STRIDE * 4) != 0) {
                throw new IllegalStateException("A face has four corners");
            }
            return new MeshShape(Mesh.of(Arrays.copyOf(vertices, size)));
        }
    }
}
