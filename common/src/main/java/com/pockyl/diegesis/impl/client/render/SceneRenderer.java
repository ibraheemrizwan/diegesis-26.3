package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.util.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.LightCoordsUtil;
import com.pockyl.diegesis.api.client.SceneBuffers;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.scene.InterfaceOptions;
import com.pockyl.diegesis.api.scene.InventoryLayout;
import com.pockyl.diegesis.api.scene.LightDefinition;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.PlaceSurface;
import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.scene.PourOptions;
import com.pockyl.diegesis.api.scene.SlotBinding;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.api.scene.Visual;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.impl.action.StationAction.Target;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.ClientStations;
import com.pockyl.diegesis.impl.client.debug.DebugView;
import com.pockyl.diegesis.impl.client.fx.LiquidRenderer;
import com.pockyl.diegesis.impl.client.fx.SceneParticles;
import com.pockyl.diegesis.impl.client.focus.FocusController;
import com.pockyl.diegesis.impl.client.focus.FocusInput;
import com.pockyl.diegesis.impl.client.focus.Projector;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;
import com.pockyl.diegesis.impl.client.scene.PlacedItem;
import com.pockyl.diegesis.impl.client.scene.PlacedShape;
import com.pockyl.diegesis.impl.client.scene.SceneInstance;

import com.pockyl.diegesis.platform.ClientPlatform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Draws every shown station's scene into the level, after the entities, with the same render types as entities and
 * item frames - so shader packs light and shade them like the rest of the world. Carried things cast a shadow of
 * their own shape onto the work surface. Also captures the frame's camera for the cursor ray.
 */
public final class SceneRenderer {
    /** Stations farther than this (blocks) are not drawn. */
    private static final double RENDER_RANGE = 40.0;
    /** Shadows and highlights: block-atlas geometry, see-through, back faces culled (no double layers in a shadow). */
    private static final RenderType OVERLAY = RenderTypes.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);
    /** Everything see-through of the scene goes here, in a buffer of its own: it stays one sorted batch. */
    private static final RenderType CLEAR = DiegesisRenderTypes.clearBlockSheet();
    private static SceneBuffers.BufferSource clearBuffers;
    /**
     * Everything else of the scene. Not the game's buffer source: see-through things write no depth, so the opaque ones must
     * be on screen before them, and Oculus and Iris swap the game's source for one that ignores a flush of a single type and
     * draws it all later, over the liquid.
     */
    private static SceneBuffers.BufferSource sceneBuffers;
    private static final Identifier HIGHLIGHT_SPRITE = Identifier.withDefaultNamespace("block/white_concrete");
    /** Shadow lean per pixel of height: light from above, a little from the front left, like the GUI's light. */
    private static final float SHADOW_SHEAR_X = -0.22F;
    private static final float SHADOW_SHEAR_Z = 0.3F;
    /** Where the scene's light comes from, for highlights on liquids: the opposite of the shadows' lean. */
    private static final Vector3fc LIGHT_DIRECTION = new Vector3f(-SHADOW_SHEAR_X, 1.0F, -SHADOW_SHEAR_Z).normalize();
    private static final Set<Identifier> WARNED_MODELS = new HashSet<>();
    /** Pools of light: a soft round gradient made in code (no texture file), laid on the surface. */
    private static RenderType pool;
    private static final List<Count> PENDING_COUNTS = new ArrayList<>();
    /** What gets an outline this frame (the usable object under the cursor), drawn once the scene is done. */
    private static final List<Consumer<SceneBuffers>> PENDING_OUTLINES = new ArrayList<>();
    private static int extractedSubmissions;
    private static boolean extractedOutline;
    public static int geometrySubmissions() {return extractedSubmissions;}
    private static int outlineColor = 0xFFFFFF;
    /** Thickness of the board under a laid-out inventory, in pixels. */
    private static final float BOARD_THICKNESS = 1.0F;

    private static long lastFrame = Util.getNanos();
    /** The interface options of the station being drawn. */
    private static InterfaceOptions ui = InterfaceOptions.DEFAULT;
    /** The frame's camera and level pose (camera-relative, not turned), for the debug view's labels. */
    private static Camera debugCamera;
    private static PoseStack debugPose;

    private SceneRenderer() {
    }

    private static final net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey<List<Consumer<net.minecraft.client.renderer.SubmitNodeCollector>>> FRAME =
            net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey.create(() -> "diegesis_scene");
    public static void extract(net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext context) {
        var state=context.levelState();
        extractedOutline=false;
        SceneBuffers.beginFrame();
        try {
            render(new PoseStack(),state.cameraRenderState.viewRotationMatrix,state.cameraRenderState.projectionMatrix,context.camera());
        } finally {
            var frame=SceneBuffers.finishFrame();
            state.setData(FRAME,frame);extractedSubmissions=frame.size();
            // Native outline submits are processed by the game's outline pass.
            if(extractedOutline) state.shouldShowEntityOutlines=true;
        }
    }
    public static void collect(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        for(var draw:context.levelState().getDataOrDefault(FRAME,List.of())) draw.accept(context.submitNodeCollector());
    }

    /**
     * Called by the loader's level-render hook after the entities.
     *
     * @param poseStack  the level pose stack: relative to the camera, not turned to it (the view rotation is in the model-view
     *                   matrix the buffers are drawn with)
     * @param modelView  the frame's view matrix: the camera's rotation
     * @param projection the frame's projection matrix
     */
    public static void render(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, Camera camera) {
        Projector.capture(projection, new Matrix4f(modelView).mul(poseStack.last().pose()), camera.position());
        SmoothPass.begin(projection, modelView);
        long now = Util.getNanos();
        float dt = Math.min((now - lastFrame) / 1.0e9F, 0.1F);
        lastFrame = now;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (FocusController.get().isInteractive()) {
            FocusInput.get().frameFromRenderer();
        }
        Vec3 cameraPos = camera.position();
        debugCamera = camera;
        debugPose = new PoseStack();
        debugPose.last().pose().set(poseStack.last().pose());
        debugPose.last().normal().set(poseStack.last().normal());
        List<ClientStation> stations = new ArrayList<>(ClientStations.get().all());
        if (stations.isEmpty()) {
            return;
        }
        if (clearBuffers == null) { clearBuffers=SceneBuffers.immediate();sceneBuffers=SceneBuffers.immediate(); }
        RenderType translucent = Sheets.translucentBlockItemSheet();
        SceneBuffers scene = type -> type == translucent ? clearBuffers.getBuffer(CLEAR) : sceneBuffers.getBuffer(type);
        for (ClientStation station : stations) {
            station.frame(dt);
            if (station.scene() == null || station.pos().distToCenterSqr(cameraPos) > RENDER_RANGE * RENDER_RANGE) {
                continue;
            }
            // Each station draws on its own copy of the pose stack: a failure halfway can never leave the level's stack
            // unbalanced (the game checks it and crashes).
            PoseStack own = new PoseStack();
            own.last().pose().set(poseStack.last().pose());
            own.last().normal().set(poseStack.last().normal());
            try {
                renderStation(station, own, cameraPos, scene, level);
            } catch (RuntimeException e) {
                DiegesisMod.LOGGER.error("Drawing station {} at {} failed", station.type().id(), station.pos(), e);
            }
        }
        // Opaque parts first: wells, highlights and shadows lie on them and must not hide them from the depth test. Then what
        // is see-through, which nothing drawn later may cross; particles last, so that a thick liquid does not bury them.
        sceneBuffers.endLastBatch();
        sceneBuffers.endBatch(Sheets.cutoutBlockItemSheet());
        sceneBuffers.endBatch(OVERLAY);
        clearBuffers.endBatch();
        sceneBuffers.endBatch();
        flushCounts(sceneBuffers);
        if (!PENDING_OUTLINES.isEmpty()) {
            try {
                drawOutlines(minecraft);
            } finally {
                PENDING_OUTLINES.clear();
            }
        }
    }

    /**
     * The outline round the usable object under the cursor: its silhouette goes into the game's entity outline target
     * (the glowing effect's), the outline effect turns the silhouette into a line round its edge, and the game lays that
     * over the frame as it does for glowing entities.
     */
    private static void drawOutlines(Minecraft minecraft) {
        extractedOutline=true;
        var outline=SceneBuffers.immediate();
        SceneBuffers silhouettes=type -> type.outline().map(outline::getBuffer).map(consumer -> (VertexConsumer)new TintedConsumer(consumer,0xFF000000 | outlineColor)).orElse(NoVertices.INSTANCE);
        try(outline) {
            for(var draw:PENDING_OUTLINES) draw.accept(silhouettes);
            outline.endBatch();
        }
    }

    /** Whether the player can do something with a node: carry it, operate it, click it, put or take an item. */
    private static boolean isUsable(NodeDefinition definition, ClientStation station) {
        if (definition.control().isPresent()) {
            return !FocusInput.isLockedControl(station, definition);
        }
        if (definition.grabbable().isPresent()) {
            return true;
        }
        if (definition.slot().isPresent()) {
            return !station.state().getItem(definition.slot().get().slot()).isEmpty();
        }
        if (definition.output().isPresent()) {
            return !station.state().getOutput(definition.output().get().output()).isEmpty();
        }
        // A node with a hit box of its own and something to see is there to be clicked.
        return definition.collider().isPresent() && definition.visual().isPresent();
    }

    /** A copy of the pose stack's top, for drawing later. */
    private static PoseStack snapshot(PoseStack poseStack) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(poseStack.last().pose());
        copy.last().normal().set(poseStack.last().normal());
        return copy;
    }

    private static void renderStation(ClientStation station, PoseStack poseStack, Vec3 camera, SceneBuffers buffers,
            ClientLevel level) {
        BlockPos pos = station.pos();
        int light = lightAt(level, pos);
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        multiply(poseStack, station.stationToWorld(new Matrix4f()));

        SceneInstance scene = station.scene();
        ui = scene.definition().ui();
        boolean local = FocusController.get().station() == station && FocusController.get().isInteractive();
        FocusInput input = FocusInput.get();
        FocusInput.Carried carried = local ? input.carried() : null;
        float workPlane = scene.definition().workPlane();
        SceneBuffers shadows = new ShadowBuffers(buffers);
        List<float[]> lights = lightsOf(scene);

        for (NodeInstance node : scene.nodes()) {
            if (!node.isVisible()) {
                continue;
            }
            NodeDefinition definition = node.definition();
            int nodeLight = lit(light, node.matrix().getTranslation(new Vector3f()), lights);
            if (node.placed() != null) {
                renderPlaced(node, local && ui.hoverOutline() && input.isHandFree(), poseStack, buffers, shadows, nodeLight, level);
                continue;
            }
            definition.control().flatMap(control -> control.base()).ifPresent(base -> {
                poseStack.pushPose();
                multiply(poseStack, node.restMatrix());
                renderVisual(base, node.tint(), poseStack, buffers, nodeLight, level);
                poseStack.popPose();
            });
            poseStack.pushPose();
            multiply(poseStack, node.matrix());
            // Only for an empty hand: while something is held, the things it passes over are not what the player uses.
            boolean pointedAt = local && ui.hoverOutline() && input.isHandFree() && node.isHovered() && !node.isCarried()
                    && !node.isOutlineByCode() && isUsable(definition, station);
            if (pointedAt || local && ui.hoverOutline() && (input.pourTarget() == node || node.isOutlined())) {
                outlineColor = ui.outlineColor();
                PoseStack at = snapshot(poseStack);
                node.visual().ifPresent(visual -> PENDING_OUTLINES.add(target -> renderVisual(visual, 0xFFFFFFFF, at, target,
                        LightCoordsUtil.FULL_BRIGHT, level)));
                definition.slot().ifPresent(slot -> PENDING_OUTLINES.add(target -> renderSlotItem(station.state().getItem(slot.slot()),
                        slot.display(), slot.shownItemSize(), slot.slot().index(), station.age(), at, target, LightCoordsUtil.FULL_BRIGHT,
                        level)));
                definition.output().ifPresent(output -> PENDING_OUTLINES.add(target -> renderSlotItem(
                        station.state().getOutput(output.output()), output.display(), output.size(), 0, station.age(), at, target,
                        LightCoordsUtil.FULL_BRIGHT, level)));
            }
            node.visual().ifPresent(visual -> renderVisual(visual, node.tint(), poseStack, buffers, nodeLight, level));
            if (node.shape() != null) {
                renderShape(node, poseStack, buffers, nodeLight);
            }
            if (node.painter() != null && !(node.painter().smooth() && SmoothPass.defer(node.painter(), poseStack, nodeLight))) {
                node.painter().paint(poseStack, buffers, nodeLight);
            }
            definition.tank().ifPresent(binding -> {
                // The world's up (tipped by sloshing) as the node sees it: the liquid's surface stays level.
                Vector3fc slosh = node.liquid().slosh();
                Matrix4f toNode = node.matrix().invert(new Matrix4f());
                Vector3f up = toNode.transformDirection(new Vector3f(slosh.x(), 1.0F, slosh.z()).normalize()).normalize();
                Vector3f sun = toNode.transformDirection(new Vector3f(LIGHT_DIRECTION)).normalize();
                LiquidRenderer.render(binding, node.liquid(), up, sun, poseStack, buffers, nodeLight);
                if (node.floating() != null) {
                    node.floating().render(binding.inner(), node.liquid(), poseStack, buffers, nodeLight);
                }
            });
            definition.slot().ifPresent(slot -> {
                boolean marked = local && (node.isHovered() || input.isSpreadTarget(new Target.Station(slot.slot())));
                if (marked && slot.highlight()) {
                    renderHighlight(poseStack, buffers, slot.size() + 0.2F, node.isHovered() ? 1.0F : 0.66F);
                }
                ItemStack stack = station.state().getItem(slot.slot());
                renderSlotItem(stack, slot.display(), slot.shownItemSize(), slot.slot().index(), station.age(), poseStack, buffers, light,
                        level);
                boolean counted = slot.counts() == SlotBinding.Counts.ALWAYS || slot.counts() == SlotBinding.Counts.HOVER && local && node.isHovered();
                if (slot.display() != SlotBinding.ItemDisplay.HIDDEN && counted) {
                    renderCount(poseStack, buffers, stack, slot.size());
                }
            });
            definition.output().ifPresent(output -> {
                ItemStack stack = station.state().getOutput(output.output());
                renderSlotItem(stack, output.display(), output.size(), 0, station.age(), poseStack, buffers,
                        local && node.isHovered() ? LightCoordsUtil.FULL_BRIGHT : light, level);
                renderCount(poseStack, buffers, stack, output.size());
            });
            poseStack.popPose();
            if (node.isCarried() && node.visual().isPresent()) {
                // The carried object's own silhouette falls onto the work surface.
                projectShadow(poseStack, workPlane, node.carryPosition().y() - workPlane, () -> {
                    multiply(poseStack, node.matrix());
                    renderVisual(node.visual().get(), 0xFFFFFFFF, poseStack, shadows, light, level);
                });
            }
        }

        if (local && scene.inventoryLayout() != null && input.inventoryReveal() > 0.0F) {
            renderInventory(scene, input, poseStack, buffers, light, level);
        }
        if (carried != null && carried.inWorld() && carried.preview() != null) {
            renderPreview(carried, scene.definition().placing().orElseThrow(), poseStack, buffers, shadows, light, level);
        } else if (carried != null && carried.inWorld() && !station.isCarriedHidden()) {
            renderCarried(carried.stack(), carried.stationPos(), carried.lean(), carried.size(), poseStack, buffers, shadows, workPlane,
                    light, level);
            poseStack.pushPose();
            Vector3fc at = carried.stationPos();
            poseStack.translate(at.x(), at.y() + 0.4F, at.z());
            renderCount(poseStack, buffers, carried.stack(), carried.size() * 1.1F);
            poseStack.popPose();
        }
        for (ClientStation.RemoteHolding holding : station.remoteHoldings()) {
            if (!holding.item().isEmpty()) {
                renderCarried(holding.item(), holding.position(), holding.rotation(), 4.5F, poseStack, buffers, shadows, workPlane, light,
                        level);
            }
        }
        renderLightPools(scene, poseStack, buffers, workPlane);
        if (local) {
            renderPourHints(scene, input, poseStack, buffers);
        }
        if ((!station.particles().isEmpty() || !station.pourStreams().isEmpty()) && debugCamera != null) {
            Matrix4f toStation = station.stationToWorld(new Matrix4f()).invert();
            Vector3f right = toStation.transformDirection(new Vector3f(debugCamera.leftVector()).negate()).normalize();
            Vector3f up = toStation.transformDirection(new Vector3f(debugCamera.upVector())).normalize();
            Vector3f view = toStation.transformDirection(new Vector3f(debugCamera.forwardVector())).normalize();
            if (scene.definition().pouring().stream()) {
                station.pourStreams().render(poseStack, buffers, view, light, scene.definition().pouring().streamWidth());
            }
            station.particles().render(poseStack, buffers, right, up, view, light);
        }
        if (DebugView.isOn()) {
            DebugView.render(station, poseStack, buffers, debugCamera, debugPose, camera);
        }
        poseStack.popPose();
    }

    /** An item standing freely: as it is, with an outline under the cursor and its shape falling on what it stands on. */
    private static void renderPlaced(NodeInstance node, boolean outline, PoseStack poseStack, SceneBuffers buffers,
            SceneBuffers shadows, int light, ClientLevel level) {
        PlacedItem item = node.placed();
        ItemStack stack = item.stack();
        PlacedShape shape = item.shape();
        ItemPose pose = item.pose();
        int seed = item.slot().index();
        poseStack.pushPose();
        multiply(poseStack, node.matrix());
        if (outline && node.isHovered()) {
            outlineColor = ui.outlineColor();
            PoseStack at = snapshot(poseStack);
            PENDING_OUTLINES.add(target -> shape.draw(stack, at, target, LightCoordsUtil.FULL_BRIGHT, level, seed));
        }
        shape.draw(stack, poseStack, buffers, light, level, seed);
        poseStack.popPose();
        if (item.options().shadows() && shape.castsShadow() && pose != null && pose.on() < 0) {
            projectShadow(poseStack, pose.y(), 0.0F, () -> {
                multiply(poseStack, node.matrix());
                shape.draw(stack, poseStack, shadows, light, level, seed);
            });
        }
    }

    /** The stack in the hand, see-through, where it would stand; the place comes from the cursor, the look from the turn it has. */
    private static void renderPreview(FocusInput.Carried carried, PlacingOptions options, PoseStack poseStack, SceneBuffers buffers,
            SceneBuffers shadows, int light, ClientLevel level) {
        ItemPose pose = carried.preview();
        ItemStack stack = carried.stack();
        PlacedShape shape = PlacedShape.of(stack, level, options.blockModels());
        Vector3fc at = carried.stationPos();
        Matrix4f matrix = new Matrix4f().translation(at.x(), at.y(), at.z()).rotate(new org.joml.Quaternionf(pose.rotation()))
                .scale(shape.modelScale(pose.scale() * options.itemSize()));
        SceneBuffers ghost = type -> isBlockSheet(type)
                ? new TintedConsumer(buffers.getBuffer(Sheets.translucentBlockItemSheet()), options.previewColor()) : buffers.getBuffer(type);
        poseStack.pushPose();
        multiply(poseStack, matrix);
        shape.draw(stack, poseStack, ghost, light, level, 0);
        poseStack.popPose();
        if (options.shadows() && shape.castsShadow() && pose.on() < 0) {
            projectShadow(poseStack, pose.y(), 0.0F, () -> {
                multiply(poseStack, matrix);
                shape.draw(stack, poseStack, shadows, light, level, 0);
            });
        }
    }

    private static boolean isBlockSheet(RenderType type) {
        return type == Sheets.cutoutBlockItemSheet() || type == Sheets.translucentBlockItemSheet() || type == Sheets.translucentItemSheet()
               ;
    }

    private static void renderVisual(Visual visual, int tint, PoseStack poseStack, SceneBuffers buffers, int light, ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        int packedLight = visual.emissive() ? LightCoordsUtil.FULL_BRIGHT : light;
        Vector3fc anchor = visual.anchor();
        if ((tint >>> 24) == 0) {
            return;
        }
        poseStack.pushPose();
        if (visual.item().isPresent()) {
            // Items cannot be tinted through the item renderer; they are drawn as they are.
            poseStack.translate(8.0F - anchor.x(), 8.0F - anchor.y(), 8.0F - anchor.z());
            poseStack.scale(16.0F, 16.0F, 16.0F);
            SceneItems.draw(new ItemStack(visual.item().get()), ItemDisplayContext.FIXED, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, level, 0);
            poseStack.popPose();
            return;
        }
        if (visual.mesh().isPresent()) {
            renderMesh(visual, tint, poseStack, buffers, packedLight);
            poseStack.popPose();
            return;
        }
        BlockStateModel model;
        BlockState state = visual.block().orElse(null);
        boolean translucent = visual.translucent() || (tint >>> 24) < 0xFF;
        if (state != null) {
            model = SceneModels.block(state);
            translucent |= model.hasMaterialFlag(net.minecraft.client.resources.model.geometry.BakedQuad.FLAG_TRANSLUCENT);
        } else {
            Identifier id = visual.model().orElseThrow();
            model = ClientPlatform.INSTANCE.getModel(id);
            if (model == minecraft.getModelManager().getBlockStateModelSet().missingModel() && WARNED_MODELS.add(id)) {
                DiegesisMod.LOGGER.warn("Scene model {} is not loaded: put it under models/diegesis/ or register it with"
                        + " DiegesisClient.registerModel", id);
            }
        }
        poseStack.translate(-anchor.x(), -anchor.y(), -anchor.z());
        poseStack.scale(16.0F, 16.0F, 16.0F);
        VertexConsumer consumer = buffers.getBuffer(translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet());
        if (tint != Visual.WHITE) {
            consumer = new TintedConsumer(consumer, tint);
        }
        if (visual.glass() > 0.0F) {
            consumer = new GlassConsumer(consumer, visual.glass());
        }
        SceneModels.draw(poseStack.last(), consumer, state, model, 1.0F, 1.0F, 1.0F,
                packedLight, OverlayTexture.NO_OVERLAY);
        if (visual.doubleSided()) {
            // The same quads turned round, in the same render type: an open glass shows its far walls from inside.
            SceneModels.draw(poseStack.last(), new BackFaceConsumer(consumer), state, model, 1.0F,
                    1.0F, 1.0F, packedLight, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }

    /** A shape made by code: like a mesh, its texture from the node. */
    private static void renderShape(NodeInstance node, PoseStack poseStack, SceneBuffers buffers, int light) {
        var sprite = SceneSprites.get(node.shapeTexture());
        VertexConsumer consumer = buffers.getBuffer(SceneSprites.sheet(sprite,(node.tint() >>> 24) < 0xFF));
        if (node.tint() != Visual.WHITE) {
            consumer = new TintedConsumer(consumer, node.tint());
        }
        node.shape().mesh().draw(poseStack.last(), consumer, sprite, node.shapeGlows() ? LightCoordsUtil.FULL_BRIGHT : light);
    }

    /** A polygon model: the same render types and wrappers as a block model, its corners written directly. */
    private static void renderMesh(Visual visual, int tint, PoseStack poseStack, SceneBuffers buffers, int light) {
        Identifier id = visual.mesh().orElseThrow();
        com.pockyl.diegesis.impl.client.mesh.Mesh mesh = com.pockyl.diegesis.impl.client.mesh.MeshLibrary.INSTANCE.mesh(id);
        if (mesh == null) {
            if (WARNED_MODELS.add(id)) {
                DiegesisMod.LOGGER.warn("Scene mesh {} is not loaded: put it at assets/{}/diegesis/meshes/{}.obj", id, id.getNamespace(), id.getPath());
            }
            return;
        }
        Vector3fc anchor = visual.anchor();
        poseStack.translate(-anchor.x(), -anchor.y(), -anchor.z());
        boolean translucent = visual.translucent() || (tint >>> 24) < 0xFF;
        VertexConsumer consumer = buffers.getBuffer(translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet());
        if (tint != Visual.WHITE) {
            consumer = new TintedConsumer(consumer, tint);
        }
        if (visual.glass() > 0.0F) {
            consumer = new GlassConsumer(consumer, visual.glass());
        }
        var sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(visual.texture().orElseThrow());
        mesh.draw(poseStack.last(), consumer, sprite, light);
        if (visual.doubleSided()) {
            mesh.draw(poseStack.last(), new BackFaceConsumer(consumer), sprite, light);
        }
    }

    /**
     * While a vessel with liquid is in the hand, a soft mark on the opening of every tank that would take it, breathing
     * slowly; the one it points at is marked solidly.
     */
    private static void renderPourHints(SceneInstance scene, FocusInput input, PoseStack poseStack, SceneBuffers buffers) {
        PourOptions options = scene.definition().pouring();
        List<NodeInstance> candidates = input.pourCandidates();
        if (!options.hints() || candidates.isEmpty()) {
            return;
        }
        float breath = 0.75F + 0.25F * (float) Math.sin(Util.getMillis() / 1000.0 * 3.0);
        int alpha = options.hintColor() >>> 24;
        for (NodeInstance node : candidates) {
            TankBinding tank = node.definition().tank().get();
            float scale = node.matrix().getScale(new Vector3f()).x;
            float size = Math.min(tank.inner().to().x() - tank.inner().from().x(), tank.inner().to().z() - tank.inner().from().z()) * scale;
            Vector3f mouth = node.matrix().transformPosition(tank.mouth());
            float strength = node == input.pourTarget() ? 1.6F : breath;
            poseStack.pushPose();
            poseStack.translate(mouth.x, mouth.y, mouth.z);
            renderSquare(poseStack, buffers, size, Math.min(255, Math.round(alpha * strength)) << 24 | options.hintColor() & 0xFFFFFF);
            poseStack.popPose();
        }
    }

    /**
     * The player's inventory laid out in the scene: a board, a sunken well per slot, the items lying in them with their
     * counts, and the vanilla highlight on the slot under the cursor.
     */
    private static void renderInventory(SceneInstance scene, FocusInput input, PoseStack poseStack, SceneBuffers buffers,
            int light, ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        InventoryLayout layout = scene.inventoryLayout();
        layout.surface().ifPresent(surface -> {
            poseStack.pushPose();
            multiply(poseStack, layout.matrix());
            float width = layout.boardWidth();
            float depth = layout.boardDepth();
            poseStack.translate(-width / 2.0F, -BOARD_THICKNESS, -depth / 2.0F);
            poseStack.scale(width, BOARD_THICKNESS, depth);
            SceneModels.draw(poseStack.last(), buffers.getBuffer(Sheets.cutoutBlockItemSheet()),
                    surface, SceneModels.block(surface), 1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        });
        float well = layout.spacing() * 0.86F;
        float reveal = input.inventoryReveal();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            // Coming up row by row from the hotbar, each slot growing out of its centre.
            int row = slot < 9 ? 0 : 4 - slot / 9;
            float grow = Mth.clamp(reveal * 1.6F - row * 0.2F, 0.0F, 1.0F);
            if (grow <= 0.0F) {
                continue;
            }
            grow = 1.0F - (1.0F - grow) * (1.0F - grow);
            poseStack.pushPose();
            multiply(poseStack, scene.inventorySlot(slot));
            if (grow < 1.0F) {
                poseStack.scale(grow, grow, grow);
            }
            if ((layout.slotColor() >>> 24) > 0) {
                renderSquare(poseStack, buffers, well, layout.slotColor());
            }
            if (slot == input.hoveredInventorySlot() || input.isSpreadTarget(new Target.Inventory(slot))) {
                renderHighlight(poseStack, buffers, well, slot == input.hoveredInventorySlot() ? 1.0F : 0.66F);
            }
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            renderSlotItem(stack, SlotBinding.ItemDisplay.FLAT, layout.size(), slot, 0.0F, poseStack, buffers, light, level);
            renderCount(poseStack, buffers, stack, layout.spacing());
            poseStack.popPose();
        }
    }

    /**
     * A stack's count lying on the surface in the slot's near right corner (as the player sees it), like the number in
     * a vanilla slot. Nothing for single items.
     */
    private static void renderCount(PoseStack poseStack, SceneBuffers buffers, ItemStack stack, float slotSize) {
        if (!ui.showCounts() || stack.isEmpty() || stack.getCount() <= 1) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String text = String.valueOf(stack.getCount());
        float scale = slotSize * 0.42F / font.lineHeight;
        poseStack.pushPose();
        // The player's right is -x and the near side -z: the font's x runs to -x, its y (down the page) to -z.
        poseStack.translate(-slotSize / 2.0F, 0.12F, -slotSize / 2.0F);
        Matrix4f flat = new Matrix4f(-scale, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -scale, 0.0F, 0.0F, scale, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
        Matrix4f matrix = new Matrix4f(poseStack.last().pose()).mul(flat);
        float x = -font.width(text) + 0.5F;
        float y = -font.lineHeight + 1.5F;
        Matrix4f shadow = new Matrix4f(matrix).translate(1.0F, 1.0F, 0.0F);
        // Lifted a hair above its shadow (the font's z points up here).
        Matrix4f front = new Matrix4f(matrix).translate(0.0F, 0.0F, 0.4F);
        poseStack.popPose();
        if (ui.countsOnTop()) {
            // Drawn after everything else, without the depth test: an item's model can never cover its number.
            PENDING_COUNTS.add(new Count(text, x, y, shadow, front));
            return;
        }
        com.pockyl.diegesis.impl.client.render.SceneText.draw(font,text, x, y, 0xFF3F3F3F, false, shadow, buffers, Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
        com.pockyl.diegesis.impl.client.render.SceneText.draw(font,text, x, y, 0xFFFFFFFF, false, front, buffers, Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
    }

    /** Draws the counts collected this frame on top of the finished scene. */
    private static void flushCounts(SceneBuffers.BufferSource buffers) {
        if (PENDING_COUNTS.isEmpty()) {
            return;
        }
        // Everything of this frame so far must be on screen first, or items drawn later would paint over the numbers.
        buffers.endBatch();
        Font font = Minecraft.getInstance().font;
        for (Count count : PENDING_COUNTS) {
            com.pockyl.diegesis.impl.client.render.SceneText.draw(font,count.text(), count.x(), count.y(), 0xFF3F3F3F, false, count.shadow(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0, LightCoordsUtil.FULL_BRIGHT);
        }
        for (Count count : PENDING_COUNTS) {
            com.pockyl.diegesis.impl.client.render.SceneText.draw(font,count.text(), count.x(), count.y(), 0xFFFFFFFF, false, count.front(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0, LightCoordsUtil.FULL_BRIGHT);
        }
        PENDING_COUNTS.clear();
        buffers.endBatch();
    }

    /** A count waiting to be drawn on top: its text, position in font units and matrices. */
    private record Count(String text, float x, float y, Matrix4f shadow, Matrix4f front) {
    }

    /** An item in a slot: lying, standing or floating, with a small per-slot turn so a full grid looks placed by hand. */
    private static void renderSlotItem(ItemStack stack, SlotBinding.ItemDisplay display, float size, int index, float age,
            PoseStack poseStack, SceneBuffers buffers, int light, ClientLevel level) {
        if (stack.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean block = isBlockLike(stack, level);
        float jitter = ((index * 7919) % 25 - 12) * 0.7F;
        poseStack.pushPose();
        switch (display) {
            case FLAT -> {
                if (block) {
                    poseStack.translate(0.0F, size * 0.25F, 0.0F);
                    poseStack.rotate(Axis.YP.rotationDegrees(180.0F + jitter));
                } else {
                    poseStack.translate(0.0F, 0.07F, 0.0F);
                    poseStack.rotate(Axis.YP.rotationDegrees(180.0F + jitter));
                    poseStack.rotate(Axis.XP.rotationDegrees(-90.0F));
                }
            }
            case UPRIGHT -> {
                poseStack.translate(0.0F, block ? size * 0.25F : size * 0.5F, 0.0F);
                poseStack.rotate(Axis.YP.rotationDegrees(180.0F));
            }
            case FLOATING -> {
                poseStack.translate(0.0F, size * 0.55F + (float) Math.sin(age * 2.2F) * 0.35F, 0.0F);
                poseStack.rotate(Axis.YP.rotationDegrees(age * 40.0F));
            }
            case HIDDEN -> {
                poseStack.popPose();
                return;
            }
        }
        poseStack.scale(size, size, size);
        SceneItems.draw(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, poseStack, buffers,
                level, index);
        poseStack.popPose();
    }

    /** A stack on the cursor (or another player's), leaning as it moves, with its silhouette as a shadow below. */
    private static void renderCarried(ItemStack stack, Vector3fc at, Vector3fc lean, float size, PoseStack poseStack,
            SceneBuffers buffers, SceneBuffers shadows, float workPlane, int light, ClientLevel level) {
        boolean block = isBlockLike(stack, level);
        Runnable place = () -> {
            poseStack.translate(at.x(), at.y(), at.z());
            poseStack.rotate(Axis.XP.rotationDegrees(lean.x()));
            poseStack.rotate(Axis.ZP.rotationDegrees(lean.z()));
            poseStack.rotate(Axis.YP.rotationDegrees(180.0F));
            if (!block) {
                // Tipped towards the camera, so it reads as held above the table rather than lying on it.
                poseStack.rotate(Axis.XP.rotationDegrees(-62.0F));
            }
            float scale = size * 1.08F;
            poseStack.scale(scale, scale, scale);
        };
        poseStack.pushPose();
        place.run();
        SceneItems.draw(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                poseStack, buffers, level, 0);
        poseStack.popPose();
        ItemStack plain = new ItemStack(stack.getItem());
        if (!SceneItems.custom(SceneItems.resolve(plain,ItemDisplayContext.FIXED,level,0))) {
            projectShadow(poseStack, workPlane, at.y() - workPlane, () -> {
                place.run();
                SceneItems.draw(plain, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                        poseStack, shadows, level, 0);
            });
        }
    }

    /**
     * Draws {@code draw} flattened onto the work plane along the light, as a shadow: geometry at height h above the
     * plane lands h * shear to the side. {@code height} is how high the thing is held; the shadow fades with it.
     */
    private static void projectShadow(PoseStack poseStack, float planeY, float height, Runnable draw) {
        if (!ui.shadows()) {
            return;
        }
        float alpha = Math.max(0.0F, ui.shadowOpacity() * (1.0F - Math.max(height, 0.0F) * 0.05F));
        if (alpha < 0.02F) {
            return;
        }
        ShadowBuffers.alpha = alpha;
        poseStack.pushPose();
        Matrix4f flatten = new Matrix4f().translate(0.0F, planeY + 0.06F, 0.0F);
        Matrix4f shear = new Matrix4f().m10(SHADOW_SHEAR_X).m11(0.0004F).m12(SHADOW_SHEAR_Z);
        flatten.mul(shear).translate(0.0F, -planeY, 0.0F);
        poseStack.last().pose().mul(flatten);
        draw.run();
        poseStack.popPose();
    }

    /** The highlight on a slot the cursor is on (strength 1) or a drag spreads over (weaker), like vanilla's. */
    private static void renderHighlight(PoseStack poseStack, SceneBuffers buffers, float size, float strength) {
        int color = ui.highlightColor();
        int alpha = Math.round((color >>> 24) * strength);
        renderSquare(poseStack, buffers, size, alpha << 24 | color & 0xFFFFFF);
    }

    /** A flat square of colour on the surface at the slot (wells, highlights). */
    private static void renderSquare(PoseStack poseStack, SceneBuffers buffers, float size, int argb) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(HIGHLIGHT_SPRITE);
        VertexConsumer consumer = buffers.getBuffer(OVERLAY);
        PoseStack.Pose pose = poseStack.last();
        float h = size / 2.0F;
        float y = 0.04F;
        // Counter-clockwise seen from above, so the culled type keeps the top face.
        vertex(consumer, pose, -h, y, -h, sprite.getU0(), sprite.getV0(), argb);
        vertex(consumer, pose, -h, y, h, sprite.getU0(), sprite.getV1(), argb);
        vertex(consumer, pose, h, y, h, sprite.getU1(), sprite.getV1(), argb);
        vertex(consumer, pose, h, y, -h, sprite.getU1(), sprite.getV0(), argb);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int argb) {
        Vector3f point = pose.pose().transformPosition(new Vector3f(x, y, z));
        Vector3f normal = pose.normal().transform(new Vector3f(0.0F, 1.0F, 0.0F));
        consumer.addVertex(point.x, point.y, point.z, argb, u, v, OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, normal.x, normal.y,
                normal.z);
    }

    private static boolean isBlockLike(ItemStack stack, ClientLevel level) {
        return SceneItems.isBlockLike(stack,level);
    }

    /**
     * Light falling on the work surface under every light source: brighter and tighter the nearer the source is, wavering
     * with its flicker. Drawn so that it brightens the surface's own colours instead of laying a haze over them.
     */
    private static void renderLightPools(SceneInstance scene, PoseStack poseStack, SceneBuffers buffers, float workPlane) {
        for (NodeInstance node : scene.nodes()) {
            if (node.lightLevel() <= 0 || !node.isVisible() || node.definition().light().isEmpty()) {
                continue;
            }
            LightDefinition light = node.definition().light().get();
            if ((light.color() >>> 24) == 0) {
                continue;
            }
            Vector3f at = node.matrix().transformPosition(new Vector3f(light.offset()));
            PlacingOptions placing = scene.definition().placing().orElse(null);
            if (placing == null) {
                renderPool(node, light, at, workPlane, null, poseStack, buffers);
                continue;
            }
            // The light stays in the room it is in: on the surface under it, not through the board to the one below.
            for (PlaceSurface surface : placing.surfaces()) {
                if (at.y >= surface.height() && at.y <= surface.height() + surface.clearance()) {
                    renderPool(node, light, at, surface.height(), surface, poseStack, buffers);
                    surface.wall().ifPresent(wall -> renderWallPool(node, light, at, wall, surface, poseStack, buffers));
                }
            }
        }
    }

    /** The same on the wall behind a surface: a warm patch round the source, within the room. */
    private static void renderWallPool(NodeInstance node, LightDefinition light, Vector3f at, float wall, PlaceSurface surface,
            PoseStack poseStack, SceneBuffers buffers) {
        float distance = Math.max(0.0F, wall - at.z) / light.radius();
        if (distance >= 1.0F) {
            return;
        }
        float strength = node.lightLevel() / 15.0F * flicker(node, light) / (1.0F + 6.0F * distance * distance) * (1.0F - distance);
        float size = light.radius() * (0.45F + 0.75F * distance);
        float alpha = (light.color() >>> 24) / 255.0F * strength;
        if (alpha <= 0.01F) {
            return;
        }
        int rgb = light.color();
        int color = 0xFF000000 | Math.round((rgb >> 16 & 0xFF) * alpha) << 16 | Math.round((rgb >> 8 & 0xFF) * alpha) << 8
                | Math.round((rgb & 0xFF) * alpha);
        float x0 = Math.max(at.x - size, surface.from().x());
        float x1 = Math.min(at.x + size, surface.to().x());
        float y0 = Math.max(at.y - size, surface.height());
        float y1 = Math.min(at.y + size, surface.height() + surface.clearance());
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        float u0 = (x0 - at.x + size) / (size * 2.0F);
        float u1 = (x1 - at.x + size) / (size * 2.0F);
        float v0 = (y0 - at.y + size) / (size * 2.0F);
        float v1 = (y1 - at.y + size) / (size * 2.0F);
        VertexConsumer consumer = buffers.getBuffer(poolType());
        PoseStack.Pose pose = poseStack.last();
        float z = wall - 0.05F;
        vertex(consumer, pose, x0, y0, z, u0, v0, color);
        vertex(consumer, pose, x0, y1, z, u0, v1, color);
        vertex(consumer, pose, x1, y1, z, u1, v1, color);
        vertex(consumer, pose, x1, y0, z, u1, v0, color);
    }

    /** One pool of light at the height {@code plane}, cut off at the edges of {@code surface} (null: none). */
    private static void renderPool(NodeInstance node, LightDefinition light, Vector3f at, float plane, PlaceSurface surface,
            PoseStack poseStack, SceneBuffers buffers) {
        float height = Math.max(0.0F, at.y - plane) / light.radius();
        if (height >= 1.0F) {
            return;
        }
        // Inverse-square-like: a source close to the surface lights a small bright spot, a high one a wide faint one.
        float strength = node.lightLevel() / 15.0F * flicker(node, light) / (1.0F + 6.0F * height * height) * (1.0F - height);
        float size = light.radius() * (0.45F + 0.75F * height);
        float alpha = (light.color() >>> 24) / 255.0F * strength;
        if (alpha <= 0.01F) {
            return;
        }
        int rgb = light.color();
        int color = 0xFF000000 | Math.round((rgb >> 16 & 0xFF) * alpha) << 16 | Math.round((rgb >> 8 & 0xFF) * alpha) << 8
                | Math.round((rgb & 0xFF) * alpha);
        float x0 = at.x - size;
        float x1 = at.x + size;
        float z0 = at.z - size;
        float z1 = at.z + size;
        if (surface != null) {
            x0 = Math.max(x0, surface.from().x());
            x1 = Math.min(x1, surface.to().x());
            z0 = Math.max(z0, surface.from().y());
            z1 = Math.min(z1, surface.to().y());
            if (x0 >= x1 || z0 >= z1) {
                return;
            }
        }
        float u0 = (x0 - at.x + size) / (size * 2.0F);
        float u1 = (x1 - at.x + size) / (size * 2.0F);
        float v0 = (z0 - at.z + size) / (size * 2.0F);
        float v1 = (z1 - at.z + size) / (size * 2.0F);
        VertexConsumer consumer = buffers.getBuffer(poolType());
        PoseStack.Pose pose = poseStack.last();
        float y = plane + 0.05F;
        vertex(consumer, pose, x0, y, z0, u0, v0, color);
        vertex(consumer, pose, x0, y, z1, u0, v1, color);
        vertex(consumer, pose, x1, y, z1, u1, v1, color);
        vertex(consumer, pose, x1, y, z0, u1, v0, color);
    }

    /** How strong a light is this frame (1 = steady): a flame's waver, smooth and different for every light. */
    private static float flicker(NodeInstance node, LightDefinition light) {
        if (light.flicker() <= 0.0F) {
            return 1.0F;
        }
        double t = Util.getMillis() / 1000.0;
        double phase = (node.id().hashCode() & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double wave = 0.5 * Math.sin(t * 7.3 + phase) + 0.3 * Math.sin(t * 13.7 + phase * 2.0) + 0.2 * Math.sin(t * 29.1 + phase * 3.0);
        return 1.0F - light.flicker() * (float) (0.5 + 0.5 * wave);
    }

    private static RenderType poolType() {
        if (pool == null) {
            int size = 64;
            NativeImage image = new NativeImage(size, size, false);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    float distance = (float) Math.hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0) / (size / 2.0F);
                    float falloff = Mth.clamp(1.0F - distance, 0.0F, 1.0F);
                    falloff = falloff * falloff * falloff;
                    int value = Math.round(falloff * 255.0F);
                    // The falloff goes into the colour (it multiplies the surface); opaque, so nothing is discarded.
                    image.setPixel(x, y, 0xFF000000 | value << 16 | value << 8 | value);
                }
            }
            Identifier texture = DiegesisMod.id("dynamic/light_pool");
            Minecraft.getInstance().getTextureManager().register(texture,new DynamicTexture(() -> "Diegesis light pool",image));
            pool = DiegesisRenderTypes.lightPool(texture);
        }
        return pool;
    }

    /** The scene's light sources this frame: x, y, z (station pixels), level (flicker included), radius. */
    private static List<float[]> lightsOf(SceneInstance scene) {
        List<float[]> lights = new ArrayList<>();
        for (NodeInstance node : scene.nodes()) {
            if (node.lightLevel() > 0 && node.isVisible() && node.definition().light().isPresent()) {
                LightDefinition light = node.definition().light().get();
                Vector3f at = node.matrix().transformPosition(new Vector3f(light.offset()));
                lights.add(new float[] {at.x, at.y, at.z, node.lightLevel() * flicker(node, light), light.radius()});
            }
        }
        return lights;
    }

    /** The station's light, brightened by the scene's light sources near {@code at} (they fade out over their radius). */
    private static int lit(int light, Vector3f at, List<float[]> lights) {
        if (lights.isEmpty()) {
            return light;
        }
        float block = LightCoordsUtil.block(light);
        for (float[] source : lights) {
            // Soft falloff (bright near the source, a long gentle tail) instead of a straight ramp.
            float near = Math.max(0.0F, 1.0F - at.distance(source[0], source[1], source[2]) / source[4]);
            block = Math.max(block, source[3] * near * (2.0F - near));
        }
        return LightCoordsUtil.pack(Math.min(15, Math.round(block)), LightCoordsUtil.sky(light));
    }

    /** The light at the station: the brighter of the block itself and the space above it (a table top is lit from above). */
    private static int lightAt(ClientLevel level, BlockPos pos) {
        int inside = LightCoordsUtil.getLightCoords(level, pos);
        int above = LightCoordsUtil.getLightCoords(level, pos.above());
        int block = Math.max(LightCoordsUtil.block(inside), LightCoordsUtil.block(above));
        int sky = Math.max(LightCoordsUtil.sky(inside), LightCoordsUtil.sky(above));
        return LightCoordsUtil.pack(block, sky);
    }

    /** Multiplies a matrix into the pose stack, normals included (PoseStack.mulPoseMatrix leaves them alone). */
    private static void multiply(PoseStack poseStack, Matrix4f matrix) {
        PoseStack.Pose last = poseStack.last();
        last.pose().mul(matrix);
        last.normal().mul(matrix.normalize3x3(new Matrix3f()));
    }

    /** Swallows what is drawn into it. */
    private static final class NoVertices implements VertexConsumer {
        static final NoVertices INSTANCE = new NoVertices();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override public VertexConsumer setColor(int color) {return this;}
        @Override public VertexConsumer setUv3(float u,float v) {return this;}
        @Override public VertexConsumer setLineWidth(float width) {return this;}

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
                float normalZ) {
        }
    }

    /** Sends everything drawn through it into one see-through black layer: the shadow of whatever is drawn. */
    private static final class ShadowBuffers implements SceneBuffers {
        private static float alpha = 0.5F;
        private final SceneBuffers buffers;

        ShadowBuffers(SceneBuffers buffers) {
            this.buffers = buffers;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            int a = Math.round(alpha * 255.0F);
            return new TintedConsumer(buffers.getBuffer(OVERLAY), a << 24);
        }
    }
}

