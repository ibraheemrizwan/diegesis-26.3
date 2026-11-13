package com.pockyl.diegesis.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;
import com.pockyl.diegesis.impl.client.debug.DebugView;
import com.pockyl.diegesis.impl.client.render.SceneRenderer;
import com.pockyl.diegesis.impl.client.scene.SceneLibrary;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public final class DiegesisFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        com.pockyl.diegesis.impl.client.render.DiegesisRenderTypes.initialize();
        DiegesisClientRuntime.init();
        FabricNetwork.registerClient();
        ClientTickEvents.END_CLIENT_TICK.register(client -> DiegesisClientRuntime.tick());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("diegesis").then(ClientCommands.literal("debug").executes(command -> {
                    DebugView.toggle();
                    return 1;
                }))));
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents.END_EXTRACTION.register(SceneRenderer::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(SceneRenderer::collect);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SceneReloadListener());
        PreparableModelLoadingPlugin.register(
                (shared,executor) -> CompletableFuture.supplyAsync(() -> SceneLibrary.modelsToLoad(shared.resourceManager()),executor),
                (Set<Identifier> models,ModelLoadingPlugin.Context context) -> {
                    for(var id:models) context.addModel(FabricClientPlatform.key(id),net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel.blockStateModel(id));
                    context.modifyBlockModelAfterBake().register((model,modify) -> StationRegistry.find(modify.state()).filter(StationType::hidesBlock).isPresent() ? new HideableFabricModel(model) : model);
                });
    }

    /** The scene library under Fabric's reload listener id. */
    private static final class SceneReloadListener implements IdentifiableResourceReloadListener {
        @Override
        public Identifier getFabricId() {
            return SceneLibrary.ID;
        }

        @Override
        public CompletableFuture<Void> reload(SharedState shared,Executor preparations,PreparationBarrier barrier,Executor game) {
            return SceneLibrary.INSTANCE.reload(shared,preparations,barrier,game);
        }

        @Override
        public String getName() {
            return SceneLibrary.ID.toString();
        }
    }
}
