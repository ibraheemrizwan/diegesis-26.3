package com.pockyl.diegesis.mixin.client;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {@Accessor("textures") Map<String,Object> diegesis$textures();}
