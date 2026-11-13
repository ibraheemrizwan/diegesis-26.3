package com.pockyl.diegesis.impl.client.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.pockyl.diegesis.api.scene.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * A polygon model from an OBJ file, in pixels. Faces are kept as quads (a triangle repeats its last corner), because
 * that is what every render type here draws; {@link #vertices} holds {@value #STRIDE} floats per corner: x, y, z, u, v
 * (0..1 over the whole texture, v down), nx, ny, nz.
 */
public final class Mesh {
    public static final int STRIDE = 8;

    private final float[] vertices;
    private final Box bounds;

    Mesh(float[] vertices, Box bounds) {
        this.vertices = vertices;
        this.bounds = bounds;
    }

    /** A mesh of ready corners, {@value #STRIDE} floats each, four per face. */
    public static Mesh of(float[] vertices) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        Vector3f point = new Vector3f();
        for (int i = 0; i < vertices.length; i += STRIDE) {
            point.set(vertices[i], vertices[i + 1], vertices[i + 2]);
            min.min(point);
            max.max(point);
        }
        return vertices.length == 0 ? new Mesh(vertices, new Box(new Vector3f(), new Vector3f())) : new Mesh(vertices, new Box(min, max));
    }

    /** The box round the model, in its own pixels. */
    public Box bounds() {
        return bounds;
    }

    public int faces() {
        return vertices.length / STRIDE / 4;
    }

    /**
     * Where a ray in the model's pixels first meets a face, as the ray parameter, or -1. Both sides of a face count: a
     * thin part seen from behind is still there.
     */
    public float intersect(Vector3f origin, Vector3f direction) {
        float best = -1.0F;
        for (int face = 0; face < vertices.length; face += 4 * STRIDE) {
            best = closer(best, triangle(origin, direction, face, face + STRIDE, face + 2 * STRIDE));
            best = closer(best, triangle(origin, direction, face, face + 2 * STRIDE, face + 3 * STRIDE));
        }
        return best;
    }

    private static float closer(float best, float t) {
        return t >= 0.0F && (best < 0.0F || t < best) ? t : best;
    }

    /** Moller-Trumbore over three corners, given by their offsets in {@link #vertices}. */
    private float triangle(Vector3f origin, Vector3f direction, int a, int b, int c) {
        float e1x = vertices[b] - vertices[a];
        float e1y = vertices[b + 1] - vertices[a + 1];
        float e1z = vertices[b + 2] - vertices[a + 2];
        float e2x = vertices[c] - vertices[a];
        float e2y = vertices[c + 1] - vertices[a + 1];
        float e2z = vertices[c + 2] - vertices[a + 2];
        float px = direction.y * e2z - direction.z * e2y;
        float py = direction.z * e2x - direction.x * e2z;
        float pz = direction.x * e2y - direction.y * e2x;
        float determinant = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(determinant) < 1.0e-9F) {
            return -1.0F;
        }
        float inverse = 1.0F / determinant;
        float tx = origin.x - vertices[a];
        float ty = origin.y - vertices[a + 1];
        float tz = origin.z - vertices[a + 2];
        float u = (tx * px + ty * py + tz * pz) * inverse;
        if (u < 0.0F || u > 1.0F) {
            return -1.0F;
        }
        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (direction.x * qx + direction.y * qy + direction.z * qz) * inverse;
        if (v < 0.0F || u + v > 1.0F) {
            return -1.0F;
        }
        return (e2x * qx + e2y * qy + e2z * qz) * inverse;
    }

    /**
     * The model as baked quads for an item, in a block's 0..1 space: its pixels stand on the middle of the block's floor. Quads
     * know only one normal each, so round things are shaded a little harder than by {@link #draw}.
     */
    public List<BakedQuad> quads(TextureAtlasSprite sprite) {
        List<BakedQuad> quads = new ArrayList<>(faces());
        var material = BakedQuad.MaterialInfo.of(new net.minecraft.client.resources.model.sprite.Material.Baked(sprite,false),
                sprite.contents().transparency(),-1,null,0);
        for (int face=0;face<vertices.length;face+=4*STRIDE) {
            Vector3f[] points=new Vector3f[4];
            long[] uv=new long[4];
            float nx=0,ny=0,nz=0;
            for(int corner=0;corner<4;corner++) {
                int i=face+corner*STRIDE;
                points[corner]=new Vector3f(vertices[i]/16+0.5F,vertices[i+1]/16,vertices[i+2]/16+0.5F);
                uv[corner]=net.minecraft.client.model.geom.builders.UVPair.pack(
                        sprite.getU0()+vertices[i+3]*(sprite.getU1()-sprite.getU0()),
                        sprite.getV0()+vertices[i+4]*(sprite.getV1()-sprite.getV0()));
                nx+=vertices[i+5];ny+=vertices[i+6];nz+=vertices[i+7];
            }
            quads.add(new BakedQuad(points[0],points[1],points[2],points[3],uv[0],uv[1],uv[2],uv[3],
                    Direction.getApproximateNearest(nx,ny,nz),material));
        }
        return List.copyOf(quads);
    }

    private static int packNormal(float x, float y, float z) {
        return (Math.round(x * 127.0F) & 0xFF) | (Math.round(y * 127.0F) & 0xFF) << 8 | (Math.round(z * 127.0F) & 0xFF) << 16;
    }

    /** Draws the model with {@code sprite} as its texture, in the same way baked quads reach a consumer. */
    public void draw(PoseStack.Pose pose, VertexConsumer consumer, TextureAtlasSprite sprite, int light) {
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        Vector4f point = new Vector4f();
        Vector3f normal = new Vector3f();
        float u0 = sprite.getU0();
        float uSpan = sprite.getU1() - u0;
        float v0 = sprite.getV0();
        float vSpan = sprite.getV1() - v0;
        for (int i = 0; i < vertices.length; i += STRIDE) {
            point.set(vertices[i], vertices[i + 1], vertices[i + 2], 1.0F);
            matrix.transform(point);
            normal.set(vertices[i + 5], vertices[i + 6], vertices[i + 7]);
            normalMatrix.transform(normal).normalize();
            consumer.addVertex(point.x, point.y, point.z, 0xFFFFFFFF, u0 + vertices[i + 3] * uSpan, v0 + vertices[i + 4] * vSpan,
                    OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z);
        }
    }
}
