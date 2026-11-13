package com.pockyl.diegesis.impl.client.focus;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The camera of the last rendered frame, kept to turn the cursor into a ray in the world and world points into
 * screen points. Captured from the level render, so it matches what the player sees (view bobbing, FOV changes and all).
 */
public final class Projector {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean valid;

    private Projector() {
    }

    /**
     * @param projection the projection matrix of the level render
     * @param view       the camera rotation (the level render's pose stack, without translation)
     * @param cameraPos  the camera position in the world
     */
    public static void capture(Matrix4f projection, Matrix4f view, Vec3 cameraPos) {
        projection.mul(view, VIEW_PROJECTION);
        VIEW_PROJECTION.invert(INVERSE);
        camera = cameraPos;
        valid = true;
    }

    public static boolean isValid() {
        return valid;
    }

    public static Vec3 camera() {
        return camera;
    }

    /** The ray under a GUI position: origin is the camera, direction in world space (unit length). */
    public static Ray rayAt(double guiX, double guiY) {
        Minecraft minecraft = Minecraft.getInstance();
        float ndcX = (float) (2.0 * guiX / minecraft.getWindow().getGuiScaledWidth() - 1.0);
        float ndcY = (float) (1.0 - 2.0 * guiY / minecraft.getWindow().getGuiScaledHeight());
        Vector4f near = INVERSE.transform(new Vector4f(ndcX, ndcY, 1.0F, 1.0F));
        Vector4f far = INVERSE.transform(new Vector4f(ndcX, ndcY, com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()?0.0F:-1.0F, 1.0F));
        Vector3f nearPoint = new Vector3f(near.x / near.w, near.y / near.w, near.z / near.w);
        Vector3f farPoint = new Vector3f(far.x / far.w, far.y / far.w, far.z / far.w);
        Vector3f direction = farPoint.sub(nearPoint).normalize();
        return new Ray(camera.add(nearPoint.x, nearPoint.y, nearPoint.z), direction);
    }

    /** A world point in GUI coordinates, or null when it is behind the camera. */
    public static Vector2f toScreen(Vec3 world) {
        Vector4f clip = VIEW_PROJECTION.transform(new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y),
                (float) (world.z - camera.z), 1.0F));
        if (clip.w <= 1.0e-4F) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float x = (clip.x / clip.w + 1.0F) * 0.5F * minecraft.getWindow().getGuiScaledWidth();
        float y = (1.0F - clip.y / clip.w) * 0.5F * minecraft.getWindow().getGuiScaledHeight();
        return new Vector2f(x, y);
    }

    /** A ray: world origin and unit direction. */
    public record Ray(Vec3 origin, Vector3f direction) {
    }
}
