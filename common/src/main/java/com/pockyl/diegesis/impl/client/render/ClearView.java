package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.ClearViewOptions;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.focus.FocusController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the camera's view of the station free ({@link ClearViewOptions}). A cone runs from the camera to what it looks at; every
 * vertex of what is inside fades by how deep it lies in the cone, so a figure dissolves softly from the middle of the view
 * outwards. Entities are drawn before the scene and, faded, write no depth: the scene comes out whole, wherever they stand.
 * Everything here is in view space (the camera at the origin); entities and particles arrive relative to the camera but not yet
 * turned to it (the level's view rotation is in the model-view matrix), so their vertices are turned here.
 */
public final class ClearView {
    /** Width of the cone at the camera, in blocks. */
    private static final float NEAR_RADIUS = 0.2F;
    /** How far past what the camera looks at things still fade, in blocks: what stands behind the station hides nothing. */
    private static final float BEHIND = 0.5F;
    private static final Pattern TEXTURE = Pattern.compile("Optional\\[([a-z0-9_.-]+:[a-z0-9_/.-]+)]");
    private static final Map<RenderType, Optional<RenderType>> GHOSTS = new HashMap<>();
    private static final Fading PARTICLES = new Fading();

    private static final Matrix4f VIEW = new Matrix4f();
    private static final Vector3f AXIS = new Vector3f();
    private static final Vector3f POINT = new Vector3f();
    private static float strength;
    private static float length;
    private static float radius;
    private static float soft;
    private static boolean entities;
    private static boolean particles;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static Entity current;

    private ClearView() {
    }

    /**
     * Called at the start of the level's rendering.
     *
     * @param view the level's view matrix: the camera's rotation, no translation
     */
    public static void begin(Matrix4f view, Camera camera) {
        strength = 0.0F;
        FocusController focus = FocusController.get();
        ClientStation station = focus.station();
        if (!focus.isActive() || station == null || station.scene() == null) {
            return;
        }
        ClearViewOptions options = station.scene().definition().clearView();
        Vec3 eye = camera.position();
        Vector3fc looked = focus.baseTarget();
        Vec3 target = station.toWorld(looked.x(), looked.y(), looked.z());
        VIEW.set(view);
        VIEW.transformDirection(AXIS.set((float) (target.x - eye.x), (float) (target.y - eye.y), (float) (target.z - eye.z)));
        length = AXIS.length();
        if (length < 0.01F) {
            return;
        }
        AXIS.div(length);
        cameraX = eye.x;
        cameraY = eye.y;
        cameraZ = eye.z;
        radius = options.radius() / 16.0F;
        soft = options.soft() / 16.0F;
        entities = options.entities();
        particles = options.particles();
        strength = focus.blend();
    }

    /** How solid a point in view space stays, 0 to 1. */
    private static float fade(float x, float y, float z) {
        float along = x * AXIS.x + y * AXIS.y + z * AXIS.z;
        if (along <= 0.0F || along >= length + BEHIND) {
            return 1.0F;
        }
        float dx = x - AXIS.x * along;
        float dy = y - AXIS.y * along;
        float dz = z - AXIS.z * along;
        float width = NEAR_RADIUS + (radius - NEAR_RADIUS) * Math.min(1.0F, along / length);
        float inside = 1.0F - smooth(((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - width) / soft);
        if (along > length) {
            inside *= 1.0F - smooth((along - length) / BEHIND);
        }
        return 1.0F - strength * inside;
    }

    private static float smooth(float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    /** Notes the entity about to be drawn. */
    public static void entity(Entity entity) {
        current = entity;
    }

    /** What the entity noted last draws into: the game's buffers, or fading ones while it may stand in the way. */
    public static SceneBuffers buffers(SceneBuffers buffers) {
        if (strength <= 0.0F || !entities || current == null || !mayBeInside(current.getBoundingBox())) {
            return buffers;
        }
        return type -> {
            RenderType ghost = GHOSTS.computeIfAbsent(type, ClearView::ghostOf).orElse(type);
            Fading fading = new Fading();
            fading.delegate = buffers.getBuffer(ghost);
            return fading;
        };
    }

    private static boolean mayBeInside(AABB box) {
        Vec3 center = box.getCenter();
        float reach = (float) Math.max(box.getSize(), 1.0) + soft;
        VIEW.transformDirection(POINT.set((float) (center.x - cameraX), (float) (center.y - cameraY), (float) (center.z - cameraZ)));
        float along = POINT.dot(AXIS);
        if (along < -reach || along > length + BEHIND + reach) {
            return false;
        }
        float distance = POINT.fma(-along, AXIS).length();
        return distance < radius + reach;
    }

    /** The translucent, depth-less twin of a textured entity type; empty for what has no texture to show (lines, text, glints). */
    private static Optional<RenderType> ghostOf(RenderType type) {
        if (type.format() != DefaultVertexFormat.ENTITY) {
            return Optional.empty();
        }
        var setup=((com.pockyl.diegesis.mixin.client.RenderTypeAccessor)type).diegesis$state();
        var binding=((com.pockyl.diegesis.mixin.client.RenderSetupAccessor)(Object)setup).diegesis$textures().get("Sampler0");
        return binding==null?Optional.empty():Optional.of(DiegesisRenderTypes.ghostEntity(((com.pockyl.diegesis.mixin.client.TextureBindingAccessor)binding).diegesis$location()));
    }

    public static RenderType entityRenderType(RenderType type) {
        return strength>0 && entities?GHOSTS.computeIfAbsent(type,ClearView::ghostOf).orElse(type):type;
    }
    public static VertexConsumer entityVertices(VertexConsumer consumer) {
        if(strength<=0 || !entities) return consumer;
        var fading=new Fading();fading.delegate=consumer;return fading;
    }
    public static int particleColor(int color,float x,float y,float z) {
        if(strength<=0 || !particles) return color;
        VIEW.transformDirection(POINT.set(x,y,z));
        int alpha=Math.round((color>>>24)*fade(POINT.x,POINT.y,POINT.z));
        return color & 0xFFFFFF | alpha<<24;
    }

    /** What a particle draws into: its vertices are relative to the camera, not yet turned to it. */
    public static VertexConsumer particles(VertexConsumer consumer) {
        if (strength <= 0.0F || !particles) {
            return consumer;
        }
        PARTICLES.delegate = consumer;
        return PARTICLES;
    }

    /** Passes vertices on with their alpha scaled by where they are. */
    private static final class Fading implements VertexConsumer {
        private VertexConsumer delegate;
        private float fade = 1.0F;

        private void look(float x, float y, float z) {
            VIEW.transformDirection(POINT.set(x, y, z));
            fade = ClearView.fade(POINT.x, POINT.y, POINT.z);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            look(x, y, z);
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, Math.round(alpha * fade));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
                float normalZ) {
            look(x, y, z);
            int alpha = Math.round((color >>> 24) * fade);
            delegate.addVertex(x, y, z, alpha << 24 | color & 0xFFFFFF, u, v, overlay, light, normalX, normalY, normalZ);
        }
        @Override public VertexConsumer setColor(int color) { return setColor(color>>16&255,color>>8&255,color&255,color>>>24); }
        @Override public VertexConsumer setUv3(float u,float v) {delegate.setUv3(u,v);return this;}
        @Override public VertexConsumer setLineWidth(float width) {delegate.setLineWidth(width);return this;}
    }
}
