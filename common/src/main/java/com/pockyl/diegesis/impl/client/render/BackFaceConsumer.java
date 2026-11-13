package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes quads on turned to face the other way: the four vertices in reverse order (the winding decides which side is
 * culled) with their normals flipped. Drawing a model once normally and once through this shows both sides of every
 * face in the same render type, so the back faces of glass stay sorted together with the liquid inside it.
 */
final class BackFaceConsumer implements VertexConsumer {
    // Per vertex: x, y, z, u, v, normal x, y, z.
    private static final int FLOATS = 8;

    private final VertexConsumer delegate;
    private final float[][] quad = new float[4][FLOATS];
    // Per vertex: colour, overlay, light.
    private final int[][] packed = new int[4][3];
    private int count;

    /** A vertex built through the chained calls; it is complete with its normal, the last element of every format used here. */
    private final float[] building = new float[FLOATS];
    private final int[] buildingPacked = {0xFFFFFFFF, 0, 0};

    BackFaceConsumer(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
            float normalZ) {
        float[] vertex = quad[count];
        vertex[0] = x;
        vertex[1] = y;
        vertex[2] = z;
        vertex[3] = u;
        vertex[4] = v;
        vertex[5] = -normalX;
        vertex[6] = -normalY;
        vertex[7] = -normalZ;
        packed[count][0] = color;
        packed[count][1] = overlay;
        packed[count][2] = light;
        if (++count < 4) {
            return;
        }
        count = 0;
        for (int i = 3; i >= 0; i--) {
            float[] out = quad[i];
            delegate.addVertex(out[0], out[1], out[2], packed[i][0], out[3], out[4], packed[i][1], packed[i][2], out[5], out[6], out[7]);
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        building[0] = x;
        building[1] = y;
        building[2] = z;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        buildingPacked[0] = (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        building[3] = u;
        building[4] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        buildingPacked[1] = u & 0xFFFF | v << 16;
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        buildingPacked[2] = u & 0xFFFF | v << 16;
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        addVertex(building[0], building[1], building[2], buildingPacked[0], building[3], building[4], buildingPacked[1], buildingPacked[2], x,
                y, z);
        return this;
    }
    @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
    @Override public VertexConsumer setUv3(float u,float v) { delegate.setUv3(u,v); return this; }
    @Override public VertexConsumer setColor(int color) { return setColor(color>>16&255,color>>8&255,color&255,color>>>24); }
}
