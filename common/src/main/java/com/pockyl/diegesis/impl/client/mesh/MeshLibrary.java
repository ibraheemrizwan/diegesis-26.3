package com.pockyl.diegesis.impl.client.mesh;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.scene.Box;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polygon models for scenes: {@code assets/<namespace>/diegesis/meshes/<path>.obj}, Wavefront OBJ with the units in
 * pixels. Positions and faces are required; {@code vt} and {@code vn} are used when present (flat normals are worked out
 * otherwise). Materials, groups and smoothing groups are ignored: a mesh has one texture, named by the visual.
 */
public final class MeshLibrary {
    public static final MeshLibrary INSTANCE = new MeshLibrary();
    private static final String DIR = "diegesis/meshes";

    private volatile Map<Identifier, Mesh> meshes = Map.of();

    private MeshLibrary() {
    }

    /** The mesh for an id, or null. */
    public Mesh mesh(Identifier id) {
        return meshes.get(id);
    }

    /** Reads every mesh; runs off the render thread while resources reload. */
    public static Map<Identifier, Mesh> load(ResourceManager manager) {
        Map<Identifier, Mesh> loaded = new HashMap<>();
        Map<Identifier, Resource> files = manager.listResources(DIR, path -> path.getPath().endsWith(".obj"));
        files.forEach((file, resource) -> {
            String path = file.getPath().substring(DIR.length() + 1, file.getPath().length() - ".obj".length());
            Identifier id = Identifier.fromNamespaceAndPath(file.getNamespace(), path);
            try (BufferedReader reader = resource.openAsReader()) {
                loaded.put(id, parse(reader));
            } catch (IOException | RuntimeException e) {
                DiegesisMod.LOGGER.error("Could not read mesh {} ({}): {}", id, file, e.toString());
            }
        });
        return loaded;
    }

    public void apply(Map<Identifier, Mesh> loaded) {
        meshes = Map.copyOf(loaded);
    }

    /** Parses OBJ text. Polygons of more than four corners become fans of quads and triangles. */
    public static Mesh parse(BufferedReader reader) throws IOException {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[][]> faces = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "v" -> positions.add(floats(parts, 3));
                case "vt" -> uvs.add(floats(parts, 2));
                case "vn" -> normals.add(floats(parts, 3));
                case "f" -> addFace(parts, faces, positions.size(), uvs.size(), normals.size());
                default -> {
                }
            }
        }
        if (faces.isEmpty()) {
            throw new IOException("no faces");
        }
        float[] vertices = new float[faces.size() * 4 * Mesh.STRIDE];
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        int out = 0;
        for (int[][] face : faces) {
            float[] flat = flatNormal(face, positions);
            for (int corner = 0; corner < 4; corner++) {
                int[] index = face[Math.min(corner, face.length - 1)];
                float[] position = positions.get(index[0]);
                float[] uv = index[1] >= 0 ? uvs.get(index[1]) : new float[2];
                float[] normal = index[2] >= 0 ? normals.get(index[2]) : flat;
                vertices[out++] = position[0];
                vertices[out++] = position[1];
                vertices[out++] = position[2];
                vertices[out++] = uv[0];
                vertices[out++] = 1.0F - uv[1];
                vertices[out++] = normal[0];
                vertices[out++] = normal[1];
                vertices[out++] = normal[2];
                min.min(new Vector3f(position));
                max.max(new Vector3f(position));
            }
        }
        return new Mesh(vertices, new Box(min, max));
    }

    private static float[] floats(String[] parts, int count) throws IOException {
        if (parts.length <= count) {
            throw new IOException("short line: " + String.join(" ", parts));
        }
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = Float.parseFloat(parts[i + 1]);
        }
        return values;
    }

    /** One polygon as {position, uv, normal} indices (-1 = none), cut into faces of at most four corners. */
    private static void addFace(String[] parts, List<int[][]> faces, int positions, int uvs, int normals) throws IOException {
        int[][] corners = new int[parts.length - 1][];
        for (int i = 1; i < parts.length; i++) {
            String[] ids = parts[i].split("/", -1);
            corners[i - 1] = new int[] {index(ids, 0, positions), index(ids, 1, uvs), index(ids, 2, normals)};
        }
        if (corners.length < 3) {
            throw new IOException("a face needs three corners");
        }
        for (int start = 1; start + 1 < corners.length; start += 2) {
            boolean quad = start + 2 < corners.length;
            faces.add(quad ? new int[][] {corners[0], corners[start], corners[start + 1], corners[start + 2]}
                    : new int[][] {corners[0], corners[start], corners[start + 1]});
        }
    }

    private static int index(String[] ids, int which, int size) throws IOException {
        if (which >= ids.length || ids[which].isEmpty()) {
            return -1;
        }
        int value = Integer.parseInt(ids[which]);
        int index = value < 0 ? size + value : value - 1;
        if (index < 0 || index >= size) {
            throw new IOException("index out of range: " + value);
        }
        return index;
    }

    private static float[] flatNormal(int[][] face, List<float[]> positions) {
        float[] a = positions.get(face[0][0]);
        float[] b = positions.get(face[1][0]);
        float[] c = positions.get(face[2][0]);
        Vector3f normal = new Vector3f(b[0] - a[0], b[1] - a[1], b[2] - a[2]).cross(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
        if (normal.lengthSquared() > 0.0F) {
            normal.normalize();
        }
        return new float[] {normal.x, normal.y, normal.z};
    }

}
