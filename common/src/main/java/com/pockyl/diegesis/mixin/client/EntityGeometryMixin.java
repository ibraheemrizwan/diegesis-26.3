package com.pockyl.diegesis.mixin.client;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.pockyl.diegesis.impl.client.render.ClearView;
@Mixin(ModelFeatureRenderer.class)
abstract class EntityGeometryMixin {
    @WrapOperation(method="prepareModel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer diegesis$fade(ModelFeatureRenderer renderer,RenderType type,Operation<VertexConsumer> original,@Local(argsOnly=true) ModelFeatureRenderer.Submit<?> submit) {
        var consumer=original.call(renderer,type);
        return submit.state() instanceof EntityRenderState?ClearView.entityVertices(consumer):consumer;
    }
}
