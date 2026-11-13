package com.pockyl.diegesis.impl.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.Function;

/**
 * Node templates: {@code assets/<namespace>/diegesis/nodes/<path>.json} holds a node without an {@code id}, and a scene
 * node with {@code "template": "<namespace>:<path>"} starts from it. The node's own fields win; objects merge field by
 * field (a {@code control} keeps the template's model and range and takes the scene's {@code control} name), lists and
 * values are replaced, and {@code null} removes a field of the template. A template may start from another template.
 *
 * <p>Expanded on the JSON before the scene is parsed, so a scene with templates parses to plain nodes.
 */
public final class NodeTemplates {
    public static final String KEY = "template";
    /** Where the template files are, under {@code assets/<namespace>/}. */
    public static final String DIR = "diegesis/nodes";
    private static final int MAX_DEPTH = 8;
    public static final String REPEAT = "repeat";
    private static final int MAX_REPEAT = 1024;

    private NodeTemplates() {
    }

    /** The scene file with every node's template applied, or why that failed. */
    public static DataResult<JsonElement> expand(JsonElement scene, Function<Identifier, JsonObject> templates) {
        if (!scene.isJsonObject()) {
            return DataResult.success(scene);
        }
        try {
            JsonObject copy = scene.getAsJsonObject().deepCopy();
            expandList(copy, "nodes", templates);
            return DataResult.success(copy);
        } catch (TemplateException e) {
            return DataResult.error(e::getMessage);
        }
    }

    /** {@code over} laid onto a copy of {@code base}: objects merge field by field, anything else is replaced. */
    public static JsonObject merge(JsonObject base, JsonObject over) {
        JsonObject result = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
            JsonElement value = entry.getValue();
            JsonElement current = result.get(entry.getKey());
            if (value.isJsonNull()) {
                result.remove(entry.getKey());
            } else if (value.isJsonObject() && current != null && current.isJsonObject()) {
                result.add(entry.getKey(), merge(current.getAsJsonObject(), value.getAsJsonObject()));
            } else {
                result.add(entry.getKey(), value.deepCopy());
            }
        }
        return result;
    }

    private static void expandList(JsonObject owner, String key, Function<Identifier, JsonObject> templates) throws TemplateException {
        JsonElement list = owner.get(key);
        if (list == null || !list.isJsonArray()) {
            return;
        }
        JsonArray expanded = new JsonArray();
        for (JsonElement node : list.getAsJsonArray()) {
            if (!node.isJsonObject()) {
                expanded.add(node);
                continue;
            }
            JsonObject result = expandNode(node.getAsJsonObject(), templates, new ArrayDeque<>());
            if (result.has(REPEAT)) {
                repeat(result, expanded);
            } else {
                expanded.add(result);
            }
        }
        owner.add(key, expanded);
    }

    /**
     * {@code "repeat": {"count": [nx, ny, nz], "step": [x, y, z]}}: the node once per cell of a grid, each {@code step}
     * further on, with ids {@code <id>_0}, {@code <id>_1}... (x counts fastest); child ids get the same ending.
     */
    private static void repeat(JsonObject node, JsonArray into) throws TemplateException {
        String where = node.has("id") ? "node " + node.get("id") : "a node";
        if (!node.has("id") || !node.get(REPEAT).isJsonObject()) {
            throw new TemplateException(where + ": \"repeat\" needs an id and {\"count\": [x, y, z], \"step\": [x, y, z]}");
        }
        JsonObject spec = node.getAsJsonObject(REPEAT);
        for (String field : spec.keySet()) {
            if (!field.equals("count") && !field.equals("step")) {
                throw new TemplateException(where + ": \"repeat\" has an unknown field \"" + field + "\"; it takes count and step");
            }
        }
        float[] count = vector(spec.get("count"), where);
        float[] step = vector(spec.get("step"), where);
        float[] start = node.has("pos") ? vector(node.get("pos"), where) : new float[3];
        int nx = (int) count[0];
        int ny = (int) count[1];
        int nz = (int) count[2];
        if (nx < 1 || ny < 1 || nz < 1 || (long) nx * ny * nz > MAX_REPEAT) {
            throw new TemplateException(where + ": \"repeat\" counts must be at least 1, " + MAX_REPEAT + " nodes at most");
        }
        int index = 0;
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    JsonObject copy = node.deepCopy();
                    copy.remove(REPEAT);
                    JsonArray pos = new JsonArray();
                    pos.add(start[0] + x * step[0]);
                    pos.add(start[1] + y * step[1]);
                    pos.add(start[2] + z * step[2]);
                    copy.add("pos", pos);
                    suffix(copy, "_" + index++);
                    into.add(copy);
                }
            }
        }
    }

    private static void suffix(JsonObject node, String ending) {
        if (node.has("id")) {
            node.addProperty("id", node.get("id").getAsString() + ending);
        }
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    suffix(child.getAsJsonObject(), ending);
                }
            }
        }
    }

    private static float[] vector(JsonElement value, String where) throws TemplateException {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw new TemplateException(where + ": expected [x, y, z] in \"repeat\" or \"pos\"");
        }
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            result[i] = value.getAsJsonArray().get(i).getAsFloat();
        }
        return result;
    }

    private static JsonObject expandNode(JsonObject node, Function<Identifier, JsonObject> templates, Deque<Identifier> chain)
            throws TemplateException {
        JsonObject result = node;
        JsonElement reference = node.get(KEY);
        if (reference != null) {
            String where = node.has("id") ? "node " + node.get("id") : "a template";
            if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString()) {
                throw new TemplateException(where + ": \"template\" must be a template id like \"diegesis:control/knob\"");
            }
            Identifier id = Identifier.tryParse(reference.getAsString());
            if (id == null) {
                throw new TemplateException(where + ": not a template id: " + reference.getAsString());
            }
            if (chain.contains(id) || chain.size() >= MAX_DEPTH) {
                throw new TemplateException(where + ": template " + id + " starts from itself (" + chain + ")");
            }
            JsonObject template = templates.apply(id);
            if (template == null) {
                throw new TemplateException(where + ": no node template " + id + " (assets/" + id.getNamespace() + "/" + DIR + "/"
                        + id.getPath() + ".json)");
            }
            if (template.has("id")) {
                throw new TemplateException("Node template " + id + " must not have an \"id\": the scene's node gives it");
            }
            chain.push(id);
            JsonObject base = expandNode(template, templates, chain);
            chain.pop();
            JsonObject own = node.deepCopy();
            own.remove(KEY);
            result = merge(base, own);
        } else {
            result = node.deepCopy();
        }
        expandList(result, "children", templates);
        return result;
    }

    private static final class TemplateException extends Exception {
        TemplateException(String message) {
            super(message, null, false, false);
        }
    }
}
