package com.pockyl.diegesis.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.diegesis.impl.client.focus.FocusController;

/**
 * Hides the chat while the player works at a station whose scene asks for it. One hook for both loaders: Forge draws
 * the chat from an overlay, Fabric from the HUD, both through this method.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
    @Inject(method="extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",at=@At("HEAD"),cancellable=true)
    private void diegesis$hideChat(CallbackInfo ci) {
        var focus=FocusController.get();if(focus.isActive() && focus.options().hideChat()) ci.cancel();
    }
}
