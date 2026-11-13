package com.pockyl.diegesis.impl.client.render;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.Identifier;
/** Linear native downsampling and premultiplied composition, with no legacy framebuffer operations. */
final class SmoothBlit {
    private static final RenderPipeline COPY=pipeline("smooth_copy",ColorTargetState.DEFAULT);
    private static final RenderPipeline OVER=pipeline("smooth_over",new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA));
    private static final ByteBufferBuilder BYTES=new ByteBufferBuilder(256);
    static void initialize() {}
    private static RenderPipeline pipeline(String name,ColorTargetState color) {
        return RenderPipelines.register(RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("diegesis","pipeline/"+name))
                .withVertexShader(Identifier.fromNamespaceAndPath("diegesis","core/smooth_blit"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("diegesis","core/smooth_blit"))
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0).withVertexBinding(0,DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS).withColorTargetState(color).withCull(false).build());
    }
    static void draw(RenderTarget source,RenderTarget target,float left,float right,float bottom,float top,boolean composite) {
        var quad=new BufferBuilder(BYTES,PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_TEX);
        quad.addVertex(left,bottom,0).setUv(0,0);quad.addVertex(right,bottom,0).setUv(1,0);
        quad.addVertex(right,top,0).setUv(1,1);quad.addVertex(left,top,0).setUv(0,1);
        var device=RenderSystem.getDevice();
        try(var mesh=quad.buildOrThrow();var vertices=device.createBuffer(() -> "Diegesis screen quad",GpuBuffer.USAGE_VERTEX,mesh.vertexBuffer())) {
            var indices=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);var indexBuffer=indices.getBuffer(6);
            try(var pass=device.createCommandEncoder().createRenderPass(() -> "Diegesis smooth blit",target.getColorTextureView(),Optional.empty(),null,OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(composite?OVER:COPY));
                pass.setUniform("Sampler0",source.getColorTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setVertexBuffer(0,vertices.slice());pass.setIndexBuffer(indexBuffer,indices.type());pass.drawIndexed(6,1,0,0,0);
            }
        }
    }
}
