package com.pockyl.diegesis.impl.client.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import com.pockyl.diegesis.impl.client.render.SceneModels;
import com.pockyl.diegesis.impl.client.render.SceneItems;
import com.pockyl.diegesis.mixin.client.*;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an item placed freely looks like and how big it really is. Any item goes: a block item with a flat icon stands there as
 * its block, everything else is drawn as the game draws it in an item frame. The box is measured on the model, so that an item
 * stands on its lowest point whichever way it is turned, and an apple that fills half its icon is as big as a sword.
 */
public final class PlacedShape {
    /** Integers per vertex in a baked quad (position, colour, uv, light, normal). */
    private static final int VERTEX_STRIDE = 8;
    private static final float THINNEST = 0.012F;
    /** A block's longest side counts for less than a sword's: it fills all of its box. */
    private static final float SOLID_SIZE = 0.8F;
    private static final Map<Object, PlacedShape> CACHE = new ConcurrentHashMap<>();

    private final BlockStateModel model;
    private final BlockState block;
    private final boolean translucent;
    private final Vector3f center;
    private final Vector3f half;
    private final float unit;
    private final int light;
    private final boolean flame;
    private final boolean custom;

    private PlacedShape(BlockStateModel model, BlockState block, Box3 box, int light, boolean flame, boolean custom) {
        this.model = model;
        this.block = block;
        this.translucent = model != null && model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
        this.center = new Vector3f(box.min).add(box.max).mul(0.5F);
        this.half = new Vector3f(box.max).sub(box.min).mul(0.5F).max(new Vector3f(THINNEST));
        float longest = Math.max(half.x, Math.max(half.y, half.z));
        boolean solid = Math.min(half.x, Math.min(half.y, half.z)) > longest * 0.6F;
        this.unit = (solid ? SOLID_SIZE : 1.0F) * 0.5F / longest;
        this.light = light;
        this.flame = flame;
        this.custom = custom;
    }

    /** Models are new after a resource reload. */
    static void clearCache() {
        CACHE.clear();
    }

    public static PlacedShape of(ItemStack stack, ClientLevel level, boolean blockModels) {
        var icon=SceneItems.resolve(stack,ItemDisplayContext.FIXED,level,0);
        BlockState state=stack.getItem() instanceof BlockItem item?item.getBlock().defaultBlockState():null;
        if(state!=null && state.hasProperty(BlockStateProperties.LIT)) state=state.setValue(BlockStateProperties.LIT,true);
        int light=state==null?0:state.getLightEmission();
        boolean flame=state!=null && (state.getBlock() instanceof TorchBlock || state.getBlock() instanceof AbstractCandleBlock);
        boolean custom=SceneItems.custom(icon);
        if(blockModels && state!=null && !icon.usesBlockLight() && !stack.has(net.minecraft.core.component.DataComponents.FOOD) && !state.is(net.minecraft.tags.BlockTags.CROPS)
                && state.getRenderShape()==RenderShape.MODEL && !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            var model=SceneModels.block(state);BlockState shown=state;
            return CACHE.computeIfAbsent(model,key -> new PlacedShape(model,shown,blockBox(model),light,flame,false));
        }
        var key=new ItemKey(stack.getItem(),stack.getComponentsPatch());
        if(icon.isAnimated()) return new PlacedShape(null,null,itemBox(icon),light,flame,custom);
        return CACHE.computeIfAbsent(key,ignored -> new PlacedShape(null,null,itemBox(icon),light,flame,custom));
    }
    private record ItemKey(net.minecraft.world.item.Item item,net.minecraft.core.component.DataComponentPatch components) {}
    private static Box3 blockBox(BlockStateModel model) {
        Box3 box=new Box3();var parts=new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
        model.collectParts(RandomSource.create(42),parts);
        for(var part:parts) for(Direction side:new Direction[]{null,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST})
            for(var quad:part.getQuads(side)) for(int i=0;i<4;i++) box.add(quad.position(i));
        return box.orElse(0,1);
    }
    private static Box3 itemBox(ItemStackRenderState model) {
        Box3 box=new Box3();var access=(ItemStateAccessor)model;
        for(int layerIndex=0;layerIndex<access.diegesis$layerCount();layerIndex++) {
            var layer=(ItemLayerAccessor)access.diegesis$layers()[layerIndex];
            var pose=new PoseStack.Pose();layer.diegesis$applyTransform(pose);
            Box3 all=new Box3(),edges=new Box3();
            for(var quad:layer.diegesis$quads().all()) for(int i=0;i<4;i++) {
                var point=new Vector3f(quad.position(i)).mulPosition(pose.pose());all.add(point);
                if(quad.direction().getAxis()!=Direction.Axis.Z) edges.add(point);
            }
            var used=!model.usesBlockLight() && !edges.isEmpty()?edges:all;
            if(!used.isEmpty()) {box.add(used.min);box.add(used.max);}
        }
        if(box.isEmpty()) model.visitExtents(box::add);
        return box.orElse(-0.5F,0.5F);
    }

    /** Light the item gives, like a block's. */
    public int light() {
        return light;
    }

    /** Whether the item burns at its top: a torch, a candle. */
    public boolean hasFlame() {
        return flame;
    }

    /** Whether the item can be flattened into a shadow: what an entity renderer draws is on textures of its own. */
    public boolean castsShadow() {
        return !custom;
    }

    /** The factor from the model to pixels for an item of {@code size} pixels. */
    public float modelScale(float size) {
        return size * unit;
    }

    /** Half the item's box in the model, for its hit box. */
    public Vector3fc half() {
        return half;
    }

    /** Half the box round the item in scene pixels, turned as given. */
    public Vector3f extents(Quaternionfc rotation, float size) {
        Matrix4f turn = new Matrix4f().rotation(new Quaternionf(rotation));
        float scale = modelScale(size);
        return new Vector3f(
                Math.abs(turn.m00()) * half.x + Math.abs(turn.m10()) * half.y + Math.abs(turn.m20()) * half.z,
                Math.abs(turn.m01()) * half.x + Math.abs(turn.m11()) * half.y + Math.abs(turn.m21()) * half.z,
                Math.abs(turn.m02()) * half.x + Math.abs(turn.m12()) * half.y + Math.abs(turn.m22()) * half.z).mul(scale);
    }

    /** Draws the item with its middle at the pose stack's origin; the stack is in model units. */
    public void draw(ItemStack stack, PoseStack poseStack, SceneBuffers buffers, int packedLight, ClientLevel level, int seed) {
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(-center.x, -center.y, -center.z);
        if (block != null) {
            VertexConsumer consumer = buffers.getBuffer(translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet());
            int color = -1;
            float red = color == -1 ? 1.0F : (color >> 16 & 0xFF) / 255.0F;
            float green = color == -1 ? 1.0F : (color >> 8 & 0xFF) / 255.0F;
            float blue = color == -1 ? 1.0F : (color & 0xFF) / 255.0F;
            SceneModels.draw(poseStack.last(), consumer, block, model, red, green, blue, packedLight,
                    OverlayTexture.NO_OVERLAY);
        } else {
            SceneItems.draw(stack, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffers,
                    level, seed);
        }
        poseStack.popPose();
    }

    /** A growing box. */
    private static final class Box3 {
        final Vector3f min = new Vector3f(Float.MAX_VALUE);
        final Vector3f max = new Vector3f(-Float.MAX_VALUE);

        void add(Vector3fc point) {
            min.min(point);
            max.max(point);
        }

        boolean isEmpty() {
            return min.x > max.x;
        }

        Box3 orElse(float low, float high) {
            if (isEmpty()) {
                add(new Vector3f(low));
                add(new Vector3f(high));
            }
            return this;
        }
    }
}
