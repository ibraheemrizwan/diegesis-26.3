package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("useUiLightmap") boolean diegesis$useUiLightmap();
    @Accessor("useUiLightmap") void diegesis$setUseUiLightmap(boolean value);
}
