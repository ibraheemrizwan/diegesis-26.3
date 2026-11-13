package com.pockyl.diegesis.impl.client.render;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
/** Resolve scene sprites across the native split block and item atlases. */
public final class SceneSprites {
    private SceneSprites() {}
    public static TextureAtlasSprite get(Identifier id) {
        var atlases=Minecraft.getInstance().getAtlasManager();
        var block=atlases.getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(id);
        return block.contents().name().equals(id) ? block : atlases.getAtlasOrThrow(AtlasIds.ITEMS).getSprite(id);
    }
    public static RenderType sheet(TextureAtlasSprite sprite, boolean translucent) {
        boolean item=sprite.atlasLocation().equals(TextureAtlas.LOCATION_ITEMS);
        return item ? (translucent ? Sheets.translucentItemSheet() : Sheets.cutoutItemSheet())
                : (translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet());
    }
}
