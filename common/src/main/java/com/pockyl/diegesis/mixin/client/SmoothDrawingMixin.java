package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
abstract class SmoothDrawingMixin {
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void diegesis$smoothDrawing(CallbackInfo ci) {com.pockyl.diegesis.impl.client.render.SmoothPass.draw();}
}
