package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.pockyl.diegesis.impl.client.render.ClearView;
/** Fade native extracted billboard colors before particle instances leave the world thread. */
@Mixin(SingleQuadParticle.class)
abstract class SingleQuadParticleMixin {
    @WrapOperation(method="extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;add(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;FFFFFFFFFFFFII)V"))
    private void diegesis$fade(QuadParticleRenderState state,SingleQuadParticle.Layer layer,float x,float y,float z,float qx,float qy,float qz,float qw,float size,float u0,float u1,float v0,float v1,int color,int light,Operation<Void> original) {
        original.call(state,layer,x,y,z,qx,qy,qz,qw,size,u0,u1,v0,v1,ClearView.particleColor(color,x,y,z),light);
    }
}
