package com.pockyl.diegesis.fabric;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.*;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import com.pockyl.diegesis.impl.client.HiddenBlocks;
/** Dynamic block visibility, with hidden state included in the geometry cache key. */
final class HideableFabricModel implements BlockStateModel {
    private final BlockStateModel original;
    HideableFabricModel(BlockStateModel original) {this.original=original;}
    @Override public void collectParts(RandomSource random,List<BlockStateModelPart> out) {original.collectParts(random,out);}
    @Override public Material.Baked particleMaterial() {return original.particleMaterial();}
    @Override public int materialFlags() {return original.materialFlags();}
    @Override public void emitQuads(QuadEmitter out,BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random,Predicate<Direction> cull) {
        if(!HiddenBlocks.isHidden(pos)) original.emitQuads(out,level,pos,state,random,cull);
    }
    @Override public Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
        Object key=original.createGeometryKey(level,pos,state,random);
        return key==null ? null : new Key(key,HiddenBlocks.isHidden(pos));
    }
    private record Key(Object model,boolean hidden) {}
}
