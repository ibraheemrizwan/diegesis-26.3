package com.pockyl.diegesis.impl.client.render;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.*;
import com.pockyl.diegesis.api.client.SceneBuffers;
import com.pockyl.diegesis.mixin.client.*;
/** Resolves native item layers and emits their actual material/tint/glint geometry through scene sinks. */
public final class SceneItems {
    private SceneItems() {}
    public static ItemStackRenderState resolve(ItemStack stack,ItemDisplayContext display,ClientLevel level,int seed) {
        var result=new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(result,stack,display,level,null,seed);
        return result;
    }
    public static boolean isBlockLike(ItemStack stack,ClientLevel level) {return resolve(stack,ItemDisplayContext.FIXED,level,0).usesBlockLight();}
    public static boolean custom(ItemStackRenderState state) {
        var access=(ItemStateAccessor)state;
        for(int i=0;i<access.diegesis$layerCount();i++) if(((ItemLayerAccessor)access.diegesis$layers()[i]).diegesis$specialRenderer()!=null) return true;
        return false;
    }
    public static void draw(ItemStack stack,ItemDisplayContext display,int light,int overlay,PoseStack pose,SceneBuffers buffers,ClientLevel level,int seed) {
        var state=resolve(stack,display,level,seed);
        var access=(ItemStateAccessor)state;
        for(int i=0;i<access.diegesis$layerCount();i++) {
            var layer=access.diegesis$layers()[i];
            var data=(ItemLayerAccessor)layer;
            if(data.diegesis$specialRenderer()!=null) {
                var frozen=new PoseStack();frozen.last().set(pose.last());
                SceneBuffers.record(collector -> data.diegesis$submit(frozen,collector,light,overlay,0));
                continue;
            }
            pose.pushPose();data.diegesis$applyTransform(pose.last());
            var instance=new QuadInstance();instance.setLightCoords(light);instance.setOverlayCoords(overlay);
            var tints=layer.tintLayers();
            for(var quad:data.diegesis$quads().all()) {
                var material=quad.materialInfo();int tint=material.tintIndex();
                instance.setColor(tint>=0 && tint<tints.size()?tints.getInt(tint):-1);
                var type=switch(data.diegesis$foilType()) {
                    case NONE -> material.itemRenderType();case STANDARD -> material.itemGlintRenderType();case SPECIAL -> material.itemGlintSpecialRenderType();
                };
                var consumer=buffers.getBuffer(type);
                if(data.diegesis$foilType()==ItemStackRenderState.FoilType.SPECIAL) {
                    var decal=pose.last().copy();
                    if(display==ItemDisplayContext.GUI) com.mojang.math.MatrixUtil.mulComponentWise(decal.pose(),0.5F);
                    else if(display.firstPerson()) com.mojang.math.MatrixUtil.mulComponentWise(decal.pose(),0.75F);
                    consumer.putBakedQuadWithGlint(pose.last(),quad,instance,decal);
                } else consumer.putBakedQuad(pose.last(),quad,instance);
            }
            pose.popPose();
        }
    }
}
