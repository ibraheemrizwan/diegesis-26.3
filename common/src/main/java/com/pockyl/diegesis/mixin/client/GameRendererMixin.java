package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pockyl.diegesis.impl.client.focus.FocusController;
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void diegesis$hideHands(CallbackInfo ci) {if(FocusController.get().isActive()) ci.cancel();}
}
