package com.pockyl.diegesis.impl.client.mesh;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import com.pockyl.diegesis.api.client.MeshShape;
import com.pockyl.diegesis.mixin.client.ItemStateAccessor;
/** Data-dependent item geometry in native item extraction; vanilla display transforms are retained. */
public final class ItemShapes {
    private static final Map<Item,Entry> ITEMS=new ConcurrentHashMap<>();
    private ItemShapes() {}
    public static void register(Item item,Function<ItemStack,MeshShape> shape,Identifier texture) { ITEMS.put(item,new Entry(shape,texture)); }
    public static void apply(ItemStack stack,ItemStackRenderState state) {
        Entry entry=ITEMS.get(stack.getItem());
        if(entry==null || state.isEmpty()) return;
        var access=(ItemStateAccessor)state;
        var layer=access.diegesis$layers()[0];
        var quads=entry.model(stack);
        layer.setQuads(quads);
        layer.setUsesBlockLight(true);
        layer.setExtents(() -> quads.all().stream().flatMap(q -> java.util.stream.IntStream.range(0,4).mapToObj(q::position)).toArray(org.joml.Vector3fc[]::new));
        access.diegesis$setLayerCount(1);
    }
    public static void clearCache() { ITEMS.values().forEach(entry -> entry.baked.clear()); }
    private static final class Entry {
        final Function<ItemStack,MeshShape> shape;
        final Identifier texture;
        final Map<DataComponentPatch,ItemQuads> baked=new LinkedHashMap<>(16,0.75F,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<DataComponentPatch,ItemQuads> eldest) { return size()>256; }
        };
        Entry(Function<ItemStack,MeshShape> shape,Identifier texture) {this.shape=shape;this.texture=texture;}
        ItemQuads model(ItemStack stack) {
            return baked.computeIfAbsent(stack.getComponentsPatch(),key -> {
                var sprite=com.pockyl.diegesis.impl.client.render.SceneSprites.get(texture);
                return ItemQuads.split(shape.apply(stack).mesh().quads(sprite));
            });
        }
    }
}
