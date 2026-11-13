package com.pockyl.diegesis.impl.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.LightCoordsUtil;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.CameraRig;
import com.pockyl.diegesis.api.scene.InventoryLayout;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.focus.FocusInput;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;
import com.pockyl.diegesis.impl.client.scene.SceneInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * The debug view for scene authors: the hit box of everything that can be hit, coloured by what it does, with node
 * ids, the laid-out inventory slots, the work plane and the camera target. On while vanilla hit boxes are (F3+B), with
 * {@code /diegesis debug}, or from code.
 */
public final class DebugView {
    private static final float[] SLOT = {0.3F, 1.0F, 0.3F};
    private static final float[] OUTPUT = {1.0F, 0.9F, 0.2F};
    private static final float[] CONTROL = {1.0F, 0.55F, 0.1F};
    private static final float[] GRABBABLE = {0.2F, 0.9F, 1.0F};
    private static final float[] CLICKABLE = {1.0F, 1.0F, 1.0F};
    private static final float[] DECOR = {0.55F, 0.55F, 0.55F};
    private static final float[] INVENTORY = {0.35F, 0.5F, 1.0F};
    private static final float[] PLANE = {1.0F, 0.3F, 1.0F};
    private static final float[] HOVERED = {1.0F, 0.2F, 0.2F};
    /** Pixels per font pixel of the node id labels. */
    private static final float LABEL_SCALE = 0.0045F;

    private static boolean forced;

    private DebugView() {
    }

    public static boolean isOn() {
        return forced || Minecraft.getInstance().debugEntries.isCurrentlyEnabled(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES);
    }

    public static void set(boolean on) {
        forced = on;
    }

    /** Switches the view and says so in the action bar. */
    public static void toggle() {
        forced = !forced;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.gui.hud.setOverlayMessage(Component.translatable(forced ? "diegesis.debug.on" : "diegesis.debug.off"), true);
        }
    }

    /**
     * Draws a station's debug lines; the pose stack is in the station's space (pixels).
     *
     * @param world the station-to-world matrix, for the labels
     */
    public static void render(ClientStation station, PoseStack poseStack, SceneBuffers buffers, Camera camera, PoseStack levelPose,
            Vec3 cameraPos) {
        SceneInstance scene = station.scene();
        // Lines first, labels after: they share the buffer source, and switching render types mid-box breaks a vertex.
        List<Runnable> labels = new ArrayList<>();
        NodeInstance hovered = FocusInput.get().hovered();
        for (NodeInstance node : scene.nodes()) {
            Box box = node.collider();
            if (box == null || !node.isVisible()) {
                continue;
            }
            float[] color = node == hovered ? HOVERED : colorOf(node.definition());
            poseStack.pushPose();
            multiply(poseStack, node.matrix());
            box(poseStack, buffers, box.from(), box.to(), color);
            poseStack.popPose();
            Vector3f top = node.matrix().transformPosition(new Vector3f((box.from().x() + box.to().x()) / 2.0F, box.to().y() + 0.6F,
                    (box.from().z() + box.to().z()) / 2.0F));
            Vec3 at = station.toWorld(top.x, top.y, top.z);
            labels.add(() -> label(node.id(), at, levelPose, buffers, camera, cameraPos, color));
        }
        InventoryLayout layout = scene.inventoryLayout();
        if (layout != null && FocusInput.get().isInventoryShown()) {
            float half = layout.spacing() / 2.0F;
            int hoveredSlot = FocusInput.get().hoveredInventorySlot();
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                poseStack.pushPose();
                multiply(poseStack, scene.inventorySlot(slot));
                box(poseStack, buffers, new Vector3f(-half, 0.0F, -half), new Vector3f(half, 0.6F, half),
                        slot == hoveredSlot ? HOVERED : INVENTORY);
                poseStack.popPose();
            }
        }
        float plane = scene.definition().workPlane();
        box(poseStack, buffers, new Vector3f(0.0F, plane, 0.0F), new Vector3f(16.0F, plane, 16.0F), PLANE);
        CameraRig rig = scene.definition().camera();
        Vector3fc target = rig.target();
        float r = 0.6F;
        box(poseStack, buffers, new Vector3f(target).sub(r, r, r), new Vector3f(target).add(r, r, r), HOVERED);
        labels.forEach(Runnable::run);
    }

    private static float[] colorOf(NodeDefinition node) {
        if (node.slot().isPresent()) {
            return SLOT;
        }
        if (node.output().isPresent()) {
            return OUTPUT;
        }
        if (node.control().isPresent()) {
            return CONTROL;
        }
        if (node.grabbable().isPresent()) {
            return GRABBABLE;
        }
        return node.collider().isPresent() ? CLICKABLE : DECOR;
    }

    private static void box(PoseStack poseStack, SceneBuffers buffers, Vector3fc from, Vector3fc to, float[] color) {
        com.pockyl.diegesis.impl.client.render.SceneLines.box(poseStack, buffers.getBuffer(RenderTypes.lines()), from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), color[0], color[1], color[2],
                1.0F);
    }

    /** A node id facing the camera, like a name tag. */
    private static void label(String text, Vec3 at, PoseStack levelPose, SceneBuffers buffers, Camera camera, Vec3 cameraPos,
            float[] color) {
        Font font = Minecraft.getInstance().font;
        levelPose.pushPose();
        levelPose.translate(at.x - cameraPos.x, at.y - cameraPos.y, at.z - cameraPos.z);
        levelPose.rotate(camera.rotation());
        float scale = LABEL_SCALE * (float) Math.max(1.0, cameraPos.distanceTo(at) * 0.5);
        levelPose.scale(-scale, -scale, scale);
        Matrix4f matrix = levelPose.last().pose();
        int rgb = (int) (color[0] * 255) << 16 | (int) (color[1] * 255) << 8 | (int) (color[2] * 255);
        com.pockyl.diegesis.impl.client.render.SceneText.draw(font,text, -font.width(text) / 2.0F, 0.0F, 0xFF000000 | rgb, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH,
                0x80000000, LightCoordsUtil.FULL_BRIGHT);
        levelPose.popPose();
    }

    private static void multiply(PoseStack poseStack, Matrix4f matrix) {
        PoseStack.Pose last = poseStack.last();
        last.pose().mul(matrix);
        last.normal().mul(matrix.normalize3x3(new Matrix3f()));
    }
}
