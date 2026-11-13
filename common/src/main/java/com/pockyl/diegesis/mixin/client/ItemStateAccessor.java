package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ItemStackRenderState.class)
public interface ItemStateAccessor {
    @Accessor("layers") ItemStackRenderState.LayerRenderState[] diegesis$layers();
    @Accessor("activeLayerCount") int diegesis$layerCount();
    @Accessor("activeLayerCount") void diegesis$setLayerCount(int count);
}
