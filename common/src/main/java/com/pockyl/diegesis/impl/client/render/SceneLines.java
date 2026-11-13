package com.pockyl.diegesis.impl.client.render;
import com.mojang.blaze3d.vertex.*;
/** Measured native line vertices used by the scene collider debug view. */
public final class SceneLines {
    private SceneLines() {}
    public static void box(PoseStack pose,VertexConsumer out,float x0,float y0,float z0,float x1,float y1,float z1,float r,float g,float b,float a) {
        for(int corner=0;corner<8;corner++) for(int axis=0;axis<3;axis++) if((corner & (1<<axis))==0) {
            int end=corner | (1<<axis);
            for(int value:new int[]{corner,end}) out.addVertex(pose.last(),(value&1)==0?x0:x1,(value&2)==0?y0:y1,(value&4)==0?z0:z1)
                    .setColor(r,g,b,a).setNormal(pose.last(),axis==0?1:0,axis==1?1:0,axis==2?1:0).setLineWidth(1);
        }
    }
}
