package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** Immutable geometry transferred from extraction to the 26.3 submit renderer, without holding world objects. */
public final class CapturedMesh implements AutoCloseable {
    private byte[] data;
    private final VertexFormat format;
    private final int count;

    public CapturedMesh(MeshData mesh) {
        try (mesh) {
            ByteBuffer vertices = mesh.vertexBuffer().duplicate();
            data = new byte[vertices.remaining()];
            vertices.get(data);
            format = mesh.drawState().format();
            count = mesh.drawState().vertexCount();
        }
    }

    public Submission at(RenderType type, double x, double y, double z) {
        if (data == null) throw new IllegalStateException("Closed mesh");
        return new Submission(data, format, count, type, x, y, z);
    }

    @Override public void close() { data = null; }

    public record Submission(byte[] vertices, VertexFormat format, int count, RenderType type, double x, double y, double z) {
        public void submit(SubmitNodeCollector collector) {
            PoseStack pose = new PoseStack();
            pose.translate(x, y, z);
            collector.submitCustomGeometry(pose, type, this::emit);
        }

        private int offset(String name) {
            var element = format.getElement(name);
            return element == null ? -1 : element.offset();
        }

        public void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            ByteBuffer data = ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
            int position = offset("Position"), color = offset("Color"), uv = offset("UV0");
            int overlay = offset("UV1"), light = offset("UV2"), uv3 = offset("UV3"), normal = offset("Normal");
            int line = offset("LineWidth"), stride = format.getVertexSize();
            for (int i = 0; i < count; i++) {
                int start = i * stride;
                consumer.addVertex(pose, data.getFloat(start + position), data.getFloat(start + position + 4), data.getFloat(start + position + 8));
                if (color >= 0) consumer.setColor(data.get(start + color) & 255, data.get(start + color + 1) & 255, data.get(start + color + 2) & 255, data.get(start + color + 3) & 255);
                else consumer.setColor(255, 255, 255, 255);
                if (uv >= 0) consumer.setUv(data.getFloat(start + uv), data.getFloat(start + uv + 4));
                if (uv3 >= 0) consumer.setUv3(data.getFloat(start + uv3), data.getFloat(start + uv3 + 4));
                consumer.setUv1(overlay < 0 ? 0 : data.getShort(start + overlay) & 65535, overlay < 0 ? 10 : data.getShort(start + overlay + 2) & 65535);
                consumer.setUv2(light < 0 ? 240 : data.getShort(start + light) & 65535, light < 0 ? 240 : data.getShort(start + light + 2) & 65535);
                if (normal >= 0) consumer.setNormal(pose, data.get(start + normal) / 127.0F, data.get(start + normal + 1) / 127.0F, data.get(start + normal + 2) / 127.0F);
                else consumer.setNormal(pose, 0.0F, 1.0F, 0.0F);
                consumer.setLineWidth(line < 0 ? 1.0F : data.getFloat(start + line));
            }
        }
    }
}
