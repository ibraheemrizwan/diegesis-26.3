package com.pockyl.diegesis.mixin.client;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerAccessor {
    @Accessor("foilType") ItemStackRenderState.FoilType diegesis$foilType();
    @Accessor("quads") ItemQuads diegesis$quads();
    @Accessor("specialRenderer") net.minecraft.client.renderer.special.SpecialModelRenderer<Object> diegesis$specialRenderer();
    @Accessor("usesBlockLight") boolean diegesis$usesBlockLight();
    @Invoker("submit") void diegesis$submit(PoseStack pose,net.minecraft.client.renderer.SubmitNodeCollector collector,int light,int overlay,int outline);
    @Invoker("applyTransform") void diegesis$applyTransform(PoseStack.Pose pose);
}
