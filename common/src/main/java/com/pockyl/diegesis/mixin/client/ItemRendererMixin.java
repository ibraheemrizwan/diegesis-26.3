package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.renderer.item.*;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pockyl.diegesis.impl.client.mesh.ItemShapes;
@Mixin(ItemModelResolver.class)
abstract class ItemRendererMixin {
    @Inject(method="appendItemLayers",at=@At("RETURN"))
    private void diegesis$shape(ItemStackRenderState output,ItemStack item,ItemDisplayContext context,Level level,ItemOwner owner,int seed,CallbackInfo ci) {
        ItemShapes.apply(item,output);
    }
}
