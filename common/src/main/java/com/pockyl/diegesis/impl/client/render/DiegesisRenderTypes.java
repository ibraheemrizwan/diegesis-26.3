package com.pockyl.diegesis.impl.client.render;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
/** Native immutable pipeline state replaces legacy OpenGL state shards. */
public final class DiegesisRenderTypes {
    private static final RenderPipeline CLEAR=register("clear",RenderPipelines.ENTITY_SNIPPET,BlendFunction.TRANSLUCENT,true);
    private static final RenderPipeline GHOST=register("ghost",RenderPipelines.ENTITY_SNIPPET,BlendFunction.TRANSLUCENT,true);
    private static final RenderPipeline LIGHT=register("light_pool",RenderPipelines.ENTITY_EMISSIVE_SNIPPET,new BlendFunction(BlendFactor.DST_COLOR,BlendFactor.ONE),false);
    private static final RenderType CLEAR_TYPE=create("diegesis_clear",CLEAR,TextureAtlas.LOCATION_BLOCKS,true,true);
    private DiegesisRenderTypes() {}
    public static void initialize() {SmoothBlit.initialize();}
    private static RenderPipeline register(String name,RenderPipeline.Snippet snippet,BlendFunction blend,boolean cull) {
        return RenderPipelines.register(RenderPipeline.builder(snippet)
                .withLocation(Identifier.fromNamespaceAndPath("diegesis","pipeline/"+name))
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                .withShaderDefine("ALPHA_CUTOUT",0.004F)
                .withColorTargetState(new ColorTargetState(blend))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false))
                .withCull(cull).build());
    }
    private static RenderType create(String name,RenderPipeline pipeline,Identifier texture,boolean lightmap,boolean offset) {
        var state=RenderSetup.builder(pipeline).withTexture("Sampler0",texture).useOverlay().sortOnUpload();
        if(lightmap) state.useLightmap();
        if(offset) state.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING);
        return RenderType.create(name,state.createRenderSetup());
    }
    static RenderType lightPool(Identifier texture) {return create("diegesis_light_pool",LIGHT,texture,false,true);}
    static RenderType ghostEntity(Identifier texture) {return create("diegesis_ghost",GHOST,texture,true,false);}
    static RenderType clearBlockSheet() {return CLEAR_TYPE;}
}
