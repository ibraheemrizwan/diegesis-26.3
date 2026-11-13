package com.pockyl.diegesis.impl.client.render;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.*;
import java.lang.Math;
import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.client.*;
import com.pockyl.diegesis.impl.client.focus.FocusScreen;
/** Four-times-finer native scene rendering followed by two exact linear halvings before HUD composition. */
public final class SmoothPass {
    private static final int FINE=4,MAX_FINE_PIXELS=8_000_000,PAD=2;
    private record Pending(NodePainter painter,Matrix4f pose,Matrix3f normal,int light) {}
    private static final List<Pending> PENDING=new ArrayList<>();
    private static final Matrix4f PROJECTION=new Matrix4f(),VIEW=new Matrix4f(),VIEW_PROJECTION=new Matrix4f();
    private static TextureTarget fine,half;
    private static boolean failed;
    private static int drawnFrames;
    public static int successfulFrames() {return drawnFrames;}
    private static RenderBuffers buffers;
    private static FeatureRenderDispatcher dispatcher;
    private static ProjectionMatrixBuffer projectionBuffer;
    private SmoothPass() {}
    static void begin(Matrix4f projection,Matrix4f view) {PENDING.clear();PROJECTION.set(projection);VIEW.set(view);projection.mul(view,VIEW_PROJECTION);}
    static boolean defer(NodePainter painter,PoseStack pose,int light) {
        if(failed || !(Minecraft.getInstance().gui.screen() instanceof FocusScreen)) return false;
        PENDING.add(new Pending(painter,new Matrix4f(pose.last().pose()),new Matrix3f(pose.last().normal()),light));return true;
    }
    /** Runs in the drawing phase after world postprocessing and before GuiRenderer opens its pass. */
    public static void draw() {
        if(PENDING.isEmpty()) return;
        try {drawPending();}
        catch(RuntimeException e) {failed=true;DiegesisMod.LOGGER.error("Native smooth scene drawing failed",e);}
        finally {PENDING.clear();}
    }
    public static void release() {
        if(fine!=null) {fine.destroyBuffers();fine=null;}
        if(half!=null) {half.destroyBuffers();half=null;}
    }
    private static void drawPending() {
        var minecraft=Minecraft.getInstance();var main=minecraft.gameRenderer.mainRenderTarget();
        int[] box=measure(main.width,main.height);if(box==null) return;
        int width=box[2]-box[0],height=box[3]-box[1];
        int scale=(long)width*height*FINE*FINE<=MAX_FINE_PIXELS?FINE:2;
        fine=sized(fine,width*scale,height*scale,true);
        if(scale==FINE) half=sized(half,width*2,height*2,false);
        float left=2.0F*box[0]/main.width-1,right=2.0F*box[2]/main.width-1;
        float bottom=2.0F*box[1]/main.height-1,top=2.0F*box[3]/main.height-1;
        var narrowed=new Matrix4f().scale(2/(right-left),2/(top-bottom),1).translate(-(left+right)/2,-(bottom+top)/2,0).mul(PROJECTION);
        if(buffers==null) {
            buffers=new RenderBuffers(1);
            dispatcher=new FeatureRenderDispatcher(buffers,minecraft.getModelManager(),minecraft.getAtlasManager(),minecraft.font,minecraft.gameRenderer.gameRenderState());
            projectionBuffer=new ProjectionMatrixBuffer("Diegesis supersampling");
        }
        var savedProjection=RenderSystem.getProjectionMatrixBuffer();var savedType=RenderSystem.getProjectionType();
        var lightAccess=(com.pockyl.diegesis.mixin.client.GameRendererAccessor)minecraft.gameRenderer;
        boolean savedUiLight=lightAccess.diegesis$useUiLightmap();
        lightAccess.diegesis$setUseUiLightmap(false);
        minecraft.gameRenderer.lighting().setupFor(com.mojang.blaze3d.platform.Lighting.Entry.LEVEL);
        var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix();modelView.set(VIEW);
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(narrowed),ProjectionType.PERSPECTIVE);
        try {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(fine.getColorTexture(),new Vector4f(0),fine.getDepthTexture(),0);
            var storage=new SubmitNodeStorage();
            SceneBuffers.beginFrame();
            try(var source=SceneBuffers.immediate()) {
                for(var pending:PENDING) pending.painter().paint(poseOf(pending),source,pending.light());
                source.endBatch();
            } finally {for(var submit:SceneBuffers.finishFrame()) submit.accept(storage);}
            try(var prepared=dispatcher.prepareFrame(storage)) {
                try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Diegesis supersampling",fine.getColorTextureView(),Optional.empty(),fine.getDepthTextureView(),OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);FeatureRenderDispatcher.renderAllFeatures(pass,prepared);
                }
            } finally {buffers.endFrame();}
        } finally {
            modelView.popMatrix();RenderSystem.setProjectionMatrix(savedProjection,savedType);
            lightAccess.diegesis$setUseUiLightmap(savedUiLight);
            minecraft.gameRenderer.lighting().setupFor(com.mojang.blaze3d.platform.Lighting.Entry.ITEMS_3D);
        }
        RenderTarget last=fine;
        if(scale==FINE) {SmoothBlit.draw(fine,half,-1,1,-1,1,false);last=half;}
        SmoothBlit.draw(last,main,left,right,bottom,top,true);
        drawnFrames++;
    }
    private static PoseStack poseOf(Pending pending) {
        var pose=new PoseStack();pose.last().pose().set(pending.pose());pose.last().normal().set(pending.normal());return pose;
    }
    private static TextureTarget sized(TextureTarget target,int width,int height,boolean depth) {
        var main=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if(target==null) return new TextureTarget("Diegesis smooth scene",width,height,main.getColorTexture().getFormat(),depth?main.getDepthTexture().getFormat():null);
        if(target.width!=width || target.height!=height) target.resize(width,height);
        return target;
    }

    /**
     * The box on the screen the kept painters cover, in framebuffer pixels from the bottom left: {x0, y0, x1, y1}; null when
     * they draw nothing on it. They draw once into a consumer that only measures.
     */
    private static int[] measure(int screenWidth, int screenHeight) {
        Bounds bounds = new Bounds();
        SceneBuffers measuring = type -> bounds;
        SceneBuffers.beginFrame();
        try {for(Pending pending:PENDING) pending.painter().paint(poseOf(pending),measuring,pending.light());}
        finally {SceneBuffers.finishFrame();}
        if (bounds.empty()) {
            return null;
        }
        int x0 = Math.max(0, (int) Math.floor((bounds.minX * 0.5F + 0.5F) * screenWidth) - PAD);
        int y0 = Math.max(0, (int) Math.floor((bounds.minY * 0.5F + 0.5F) * screenHeight) - PAD);
        int x1 = Math.min(screenWidth, (int) Math.ceil((bounds.maxX * 0.5F + 0.5F) * screenWidth) + PAD);
        int y1 = Math.min(screenHeight, (int) Math.ceil((bounds.maxY * 0.5F + 0.5F) * screenHeight) + PAD);
        return x1 > x0 && y1 > y0 ? new int[] {x0, y0, x1, y1} : null;
    }

    /** Takes the vertices of a draw only to find where on the screen they land; one behind the camera covers it all. */
    private static final class Bounds implements VertexConsumer {
        private final Vector4f clip = new Vector4f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        boolean empty() {
            return minX > maxX || minY > maxY || maxX < -1.0F || minX > 1.0F || maxY < -1.0F || minY > 1.0F;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
                float normalZ) {
            addVertex(x, y, z);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            VIEW_PROJECTION.transform(clip.set(x, y, z, 1.0F));
            if (clip.w <= 1.0e-4F) {
                minX = -1.0F;
                minY = -1.0F;
                maxX = 1.0F;
                maxY = 1.0F;
                return this;
            }
            float ndcX = Math.max(-1.0F, Math.min(1.0F, clip.x / clip.w));
            float ndcY = Math.max(-1.0F, Math.min(1.0F, clip.y / clip.w));
            minX = Math.min(minX, ndcX);
            minY = Math.min(minY, ndcY);
            maxX = Math.max(maxX, ndcX);
            maxY = Math.max(maxY, ndcY);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
        @Override public VertexConsumer setColor(int color) {return this;}
        @Override public VertexConsumer setUv3(float u,float v) {return this;}
        @Override public VertexConsumer setLineWidth(float width) {return this;}
    }
}
