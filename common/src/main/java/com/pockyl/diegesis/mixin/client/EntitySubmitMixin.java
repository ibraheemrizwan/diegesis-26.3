package com.pockyl.diegesis.mixin.client;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.pockyl.diegesis.impl.client.render.ClearView;
@Mixin(SubmitNodeCollection.class)
abstract class EntitySubmitMixin {
    @ModifyVariable(method="submitModel",at=@At("HEAD"),argsOnly=true)
    private RenderType diegesis$ghostPipeline(RenderType type,@Local(argsOnly=true) Object state) {
        return state instanceof EntityRenderState?ClearView.entityRenderType(type):type;
    }
}
