package com.pockyl.diegesis.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pockyl.diegesis.api.client.SceneBuffers;

/**
 * Draws something by code at a node ({@link NodeView#setPainter}), with the scene and in it: a vanilla entity model, text on a page, items
 * lying on a table. It sorts with the scene and turns with the node.
 */
@FunctionalInterface
public interface NodePainter {
    /**
     * Called every frame while the node is visible.
     *
     * @param poseStack in the node's pixels
     * @param buffers   the scene's: what is opaque is drawn first, see-through block sheets after it, sorted
     * @param light     packed light at the node
     */
    void paint(PoseStack poseStack, SceneBuffers buffers, int light);

    /**
     * Whether this is drawn smooth: while the player works at the station, over the finished frame and supersampled, so its edges
     * have no steps (the game draws without anti-aliasing). For something hanging in front of the scene that nothing of the
     * scene covers, such as a book before the camera: it is drawn over everything, without the shader pack's light, and
     * {@link #paint} is called twice a frame (once to measure). Otherwise it is drawn with the scene.
     */
    default boolean smooth() {
        return false;
    }
}
