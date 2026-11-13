package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes vertices on with their alpha set by the angle they are seen at, as glass behaves: nearly nothing where a face
 * looks straight at the camera, thick at a grazing angle. Colour stays as it is, so a pale glass texture no longer
 * washes out what is behind it. Positions and normals reach this relative to the camera, the eye at the origin.
 */
final class GlassConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final float clarity;

    GlassConsumer(VertexConsumer delegate, float clarity) {
        this.delegate = delegate;
        this.clarity = clarity;
    }

    private float keep(float px, float py, float pz, float nx, float ny, float nz) {
        float length = (float) Math.sqrt(px * px + py * py + pz * pz);
        float facing = length < 1.0e-5F ? 1.0F : Math.abs(px * nx + py * ny + pz * nz) / length;
        float grazing = 1.0F - Math.min(1.0F, facing);
        return 1.0F - clarity * (1.0F - grazing * grazing);
    }

    @Override
    public void addVertex(float px, float py, float pz, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        int alphaValue = Math.round((color >>> 24) * keep(px, py, pz, nx, ny, nz));
        delegate.addVertex(px, py, pz, alphaValue << 24 | color & 0xFFFFFF, u, v, overlay, light, nx, ny, nz);
    }

    @Override
    public VertexConsumer addVertex(float px, float py, float pz) {
        delegate.addVertex(px, py, pz);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alphaValue) {
        // Built piece by piece the normal is not known yet: the clear value.
        delegate.setColor(red, green, blue, Math.round(alphaValue * (1.0F - clarity)));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float nx, float ny, float nz) {
        delegate.setNormal(nx, ny, nz);
        return this;
    }
    @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
    @Override public VertexConsumer setUv3(float u,float v) { delegate.setUv3(u,v); return this; }
    @Override public VertexConsumer setColor(int color) { return setColor(color>>16&255,color>>8&255,color&255,color>>>24); }
}
