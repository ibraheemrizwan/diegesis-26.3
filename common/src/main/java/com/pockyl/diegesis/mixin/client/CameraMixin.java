package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.pockyl.diegesis.impl.client.focus.FocusController;
/** Move the native camera before its FOV, view matrix and frustum are extracted. */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow private boolean detached;
    @Shadow private Entity entity;
    @Shadow protected abstract void setPosition(Vec3 pos);
    @Shadow protected abstract void setRotation(float yaw,float pitch);
    @Shadow public abstract Vec3 position();
    @Inject(method="alignWithEntity",at=@At("TAIL"))
    private void diegesis$focus(float partial,CallbackInfo ci) {
        var focus=FocusController.get();if(!focus.isActive() || entity==null) return;
        focus.frame();var eye=position();boolean detachedView=detached;
        var pose=focus.cameraPose(eye,entity.getViewYRot(partial),entity.getViewXRot(partial));if(pose==null) return;
        setRotation(pose.yaw(),pose.pitch());setPosition(pose.position());
        detached=!focus.options().hidePlayer() && (detachedView || pose.position().distanceToSqr(eye)>0.75*0.75);
    }
    @Inject(method="calculateFov",at=@At("RETURN"),cancellable=true)
    private void diegesis$fov(float partial,CallbackInfoReturnable<Float> cir) {
        var focus=FocusController.get();if(focus.isActive()) cir.setReturnValue((float)focus.fov(cir.getReturnValue()));
    }
}
