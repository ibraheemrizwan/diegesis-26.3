package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes vertices on with their colour multiplied by a tint. The block model renderer applies a colour only to tinted
 * quads (grass, leaves) and always writes alpha 1, so tints and fading go through this instead.
 */
public final class TintedConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    public TintedConsumer(VertexConsumer delegate, int argb) {
        this.delegate = delegate;
        this.alpha = (argb >>> 24) / 255.0F;
        this.red = (argb >> 16 & 0xFF) / 255.0F;
        this.green = (argb >> 8 & 0xFF) / 255.0F;
        this.blue = (argb & 0xFF) / 255.0F;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int redValue, int greenValue, int blueValue, int alphaValue) {
        delegate.setColor(Math.round(redValue * red), Math.round(greenValue * green), Math.round(blueValue * blue),
                Math.round(alphaValue * alpha));
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
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
            float normalZ) {
        int tinted = Math.round((color >>> 24) * alpha) << 24 | Math.round((color >> 16 & 0xFF) * red) << 16
                | Math.round((color >> 8 & 0xFF) * green) << 8 | Math.round((color & 0xFF) * blue);
        delegate.addVertex(x, y, z, tinted, u, v, overlay, light, normalX, normalY, normalZ);
    }
    @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
    @Override public VertexConsumer setUv3(float u,float v) { delegate.setUv3(u,v); return this; }
    @Override public VertexConsumer setColor(int color) { return setColor(color>>16&255,color>>8&255,color&255,color>>>24); }
}
