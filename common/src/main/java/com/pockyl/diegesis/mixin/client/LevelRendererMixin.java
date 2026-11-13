package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pockyl.diegesis.impl.client.render.ClearView;
@Mixin(LevelExtractor.class)
abstract class LevelRendererMixin {
    @Inject(method="extract",at=@At("HEAD"))
    private void diegesis$clearView(DeltaTracker delta,Camera camera,float partial,CallbackInfo ci) {ClearView.begin(camera.getViewRotationMatrix(new Matrix4f()),camera);}
}
