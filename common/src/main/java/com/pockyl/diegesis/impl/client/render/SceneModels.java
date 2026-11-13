package com.pockyl.diegesis.impl.client.render;
import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.*;
import net.minecraft.core.Direction;
import net.minecraft.util.*;
import net.minecraft.world.level.block.state.BlockState;
/** Native block-model parts shared by scene drawing and measured colliders. */
public final class SceneModels {
    private SceneModels() {}
    public static BlockStateModel block(BlockState state) {return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);}
    public static void draw(PoseStack.Pose pose,VertexConsumer consumer,BlockState state,BlockStateModel model,float red,float green,float blue,int light,int overlay) {
        var parts=new ArrayList<BlockStateModelPart>();model.collectParts(RandomSource.create(42),parts);
        var instance=new QuadInstance();instance.setLightCoords(light);instance.setOverlayCoords(overlay);
        var tints=state==null?List.<net.minecraft.client.color.block.BlockTintSource>of():Minecraft.getInstance().getBlockColors().getTintSources(state);
        for(var part:parts) for(Direction side:new Direction[]{null,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}) {
            for(var quad:part.getQuads(side)) {
                int index=quad.materialInfo().tintIndex();
                int tint=index>=0 && index<tints.size()?tints.get(index).color(state):-1;
                instance.setColor(ARGB.color(255,Math.round((tint>>16&255)*red),Math.round((tint>>8&255)*green),Math.round((tint&255)*blue)));
                consumer.putBakedQuad(pose,quad,instance);
            }
        }
    }
}
