package com.pockyl.diegesis.impl.client.scene;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.impl.client.ClientRegistry;
import com.pockyl.diegesis.impl.client.ClientStations;
import com.pockyl.diegesis.impl.scene.NodeTemplates;

import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the scene files, {@code assets/<namespace>/diegesis/scenes/<path>.json}, with the resource packs: F3+T reloads
 * them while the game runs, so a scene can be tuned without restarting. A broken file is reported in the log with the
 * reason, and its station shows nothing until it is fixed.
 */
public final class SceneLibrary extends SimplePreparableReloadListener<Map<Identifier, SceneDefinition>> {
    public static final SceneLibrary INSTANCE = new SceneLibrary();
    public static final Identifier ID = DiegesisMod.id("scenes");
    private static final String SCENE_DIR = "diegesis/scenes";
    private static final String MODEL_DIR = "models/diegesis";

    private volatile Map<Identifier, SceneDefinition> scenes = Map.of();
    private volatile Map<Identifier, com.pockyl.diegesis.impl.client.mesh.Mesh> preparedMeshes = Map.of();

    private SceneLibrary() {
    }

    /** The scene for an id: a file, else one registered in code. */
    public SceneDefinition scene(Identifier id) {
        SceneDefinition scene = scenes.get(id);
        return scene != null ? scene : ClientRegistry.codeScene(id).orElse(null);
    }

    @Override
    protected Map<Identifier, SceneDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, SceneDefinition> loaded = new HashMap<>();
        preparedMeshes = com.pockyl.diegesis.impl.client.mesh.MeshLibrary.load(manager);
        Map<Identifier, JsonObject> templates = loadTemplates(manager);
        Map<Identifier, Resource> files = manager.listResources(SCENE_DIR, path -> path.getPath().endsWith(".json"));
        files.forEach((file, resource) -> {
            String path = file.getPath().substring(SCENE_DIR.length() + 1, file.getPath().length() - ".json".length());
            Identifier id = Identifier.fromNamespaceAndPath(file.getNamespace(), path);
            try (Reader reader = resource.openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                DataResult<SceneDefinition> result = NodeTemplates.expand(json, templates::get)
                        .flatMap(expanded -> SceneDefinition.CODEC.parse(JsonOps.INSTANCE, expanded));
                result.resultOrPartial(error -> DiegesisMod.LOGGER.error("Scene {} ({}) is broken: {}", id, file, error))
                        .filter(scene -> result.error().isEmpty())
                        .ifPresent(scene -> loaded.put(id, scene));
            } catch (Exception e) {
                DiegesisMod.LOGGER.error("Could not read scene {} ({})", id, file, e);
            }
        });
        return loaded;
    }

    /** The node templates, {@code assets/<namespace>/diegesis/nodes/<path>.json}. */
    private static Map<Identifier, JsonObject> loadTemplates(ResourceManager manager) {
        Map<Identifier, JsonObject> templates = new HashMap<>();
        String dir = NodeTemplates.DIR;
        manager.listResources(dir, path -> path.getPath().endsWith(".json")).forEach((file, resource) -> {
            Identifier id = Identifier.fromNamespaceAndPath(file.getNamespace(), file.getPath().substring(dir.length() + 1, file.getPath().length() - ".json".length()));
            try (Reader reader = resource.openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (json.isJsonObject()) {
                    templates.put(id, json.getAsJsonObject());
                } else {
                    DiegesisMod.LOGGER.error("Node template {} ({}) is not a JSON object", id, file);
                }
            } catch (Exception e) {
                DiegesisMod.LOGGER.error("Could not read node template {} ({})", id, file, e);
            }
        });
        return templates;
    }

    @Override
    protected void apply(Map<Identifier, SceneDefinition> loaded, ResourceManager manager, ProfilerFiller profiler) {
        scenes = Map.copyOf(loaded);
        com.pockyl.diegesis.impl.client.mesh.MeshLibrary.INSTANCE.apply(preparedMeshes);
        DefaultColliders.clearCache();
        PlacedShape.clearCache();
        com.pockyl.diegesis.impl.client.mesh.ItemShapes.clearCache();
        DiegesisMod.LOGGER.info("Loaded {} Diegesis scenes, {} meshes", loaded.size(), preparedMeshes.size());
        ClientStations.get().onScenesReloaded();
    }

    /**
     * The block models every scene may use without registering them: all of {@code models/diegesis/}, plus the ones
     * registered in code. Read while the models load (before the scenes).
     */
    public static Set<Identifier> modelsToLoad(ResourceManager manager) {
        Set<Identifier> models = manager.listResources(MODEL_DIR, path -> path.getPath().endsWith(".json")).keySet().stream()
                .map(file -> Identifier.fromNamespaceAndPath(file.getNamespace(), file.getPath().substring("models/".length(), file.getPath().length() - ".json".length())))
                .collect(Collectors.toSet());
        models.addAll(ClientRegistry.extraModels());
        return models;
    }

    /** Every model id the loaded scenes use, for warnings about models that were not loaded. */
    public List<Identifier> usedModels() {
        return scenes.values().stream().flatMap(scene -> {
            java.util.List<Identifier> ids = new java.util.ArrayList<>();
            scene.forEachNode(node -> {
                node.visual().flatMap(v -> v.model()).ifPresent(ids::add);
                node.control().flatMap(c -> c.base()).flatMap(v -> v.model()).ifPresent(ids::add);
            });
            return ids.stream();
        }).distinct().toList();
    }
}
