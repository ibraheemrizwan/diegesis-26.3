package com.pockyl.diegesis.impl.client.render;
import net.minecraft.client.gui.Font;
import org.joml.Matrix4f;
import com.pockyl.diegesis.api.client.SceneBuffers;
/** Native prepared glyphs preserve font rendering, bidi shaping and scene tint/shadow consumers. */
public final class SceneText {
    private SceneText() {}
    public static void draw(Font font,String text,float x,float y,int color,boolean shadow,Matrix4f pose,SceneBuffers buffers,Font.DisplayMode mode,int background,int light) {
        font.prepareText(text,x,y,color,shadow,background).visit(new Font.GlyphVisitor() {
            @Override public void acceptRenderable(net.minecraft.client.gui.font.TextRenderable renderable) {
                renderable.render(pose,buffers.getBuffer(renderable.renderType(mode)),light,false);
            }
        });
    }
}
