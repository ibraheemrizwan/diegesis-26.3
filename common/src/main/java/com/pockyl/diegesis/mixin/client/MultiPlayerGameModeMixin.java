package com.pockyl.diegesis.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;

/**
 * Turns the use of a station block into opening the station, before anything is sent to the server: the block's own
 * GUI never opens, and a client-only station works on servers without the library. The loaders' use-block events fire
 * on the server too and cannot stop the use packet, hence one client hook for both.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void diegesis$openStation(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = DiegesisClientRuntime.onUseBlock(player, hand, hit);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
