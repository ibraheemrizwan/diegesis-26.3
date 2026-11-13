package com.pockyl.diegesis.api.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.impl.client.ClientRegistry;
import com.pockyl.diegesis.impl.client.ClientStations;
import com.pockyl.diegesis.impl.client.debug.DebugView;
import com.pockyl.diegesis.impl.client.focus.FocusController;
import com.pockyl.diegesis.impl.client.fx.LiquidRenderer;

import java.util.Optional;

/**
 * The library's entry point for client code. Call the {@code register} methods from your client setup; never from
 * common code (a dedicated server has no client classes).
 */
public final class DiegesisClient {
    private DiegesisClient() {
    }

    /** Client code (animations, sounds, effects) for the stations of a type. */
    public static void registerBehavior(Identifier station, StationBehavior.Factory factory) {
        ClientRegistry.registerBehavior(station, factory);
    }

    /**
     * A scene built in code instead of a JSON file. A JSON file with the same id
     * ({@code assets/<namespace>/diegesis/scenes/<path>.json}) still wins, so resource packs can restyle it.
     */
    public static void registerScene(Identifier id, SceneDefinition scene) {
        ClientRegistry.registerScene(id, scene);
    }

    /**
     * Loads a block model that scenes use but that is not under {@code models/diegesis/} (every model there is loaded
     * by itself). {@code model} is a model id: {@code mymod:block/flask} for {@code assets/mymod/models/block/flask.json}.
     */
    public static void registerModel(Identifier model) {
        ClientRegistry.registerModel(model);
    }

    /**
     * Gives an item a look made from its data: in inventories, hands, frames and on shelves it is the shape {@code shape} makes
     * for the stack, with {@code texture} (a sprite of the block atlas) on it. The shape stands on the middle of the block's floor,
     * in pixels. The item still needs a model file, for its display transforms; shapes are kept per item data.
     */
    public static void registerItemShape(net.minecraft.world.item.Item item, java.util.function.Function<net.minecraft.world.item.ItemStack, MeshShape> shape,
            Identifier texture) {
        com.pockyl.diegesis.impl.client.mesh.ItemShapes.register(item, shape, texture);
    }

    /** A scene particle style, for emitters, effects and {@link StationView#spawnParticles}. */
    public static void registerParticle(Identifier id, ParticleStyle style) {
        ClientRegistry.registerParticle(id, style);
    }

    /** How a liquid looks in tanks (its id is a fluid's or one the mod made up for a reagent). */
    public static void registerLiquid(Identifier id, LiquidLook look) {
        ClientRegistry.registerLiquid(id, look);
    }

    /** An effect the stations' logic can play with {@code StationContext.effect(id, ...)}. */
    /** The colour a mixture is drawn in (the liquids' looks blended by amount), ARGB: for particles of the same colour. */
    public static int liquidColor(TankContents contents) {
        return LiquidRenderer.look(contents).color();
    }

    public static void registerEffect(Identifier id, EffectHandler handler) {
        ClientRegistry.registerEffect(id, handler);
    }

    /**
     * Shows or hides the debug view: the hit box of every node (coloured by role, with its id), the laid-out inventory
     * slots, the work plane and the camera target. Also on while vanilla hit boxes are (F3+B) and with the client
     * command {@code /diegesis debug}.
     */
    public static void setDebugView(boolean on) {
        DebugView.set(on);
    }

    public static boolean isDebugView() {
        return DebugView.isOn();
    }

    /** Whether the local player is working at a station (the camera is at it). */
    public static boolean isFocused() {
        return FocusController.get().isActive();
    }

    /**
     * How far the camera has flown over from one station to a neighbour ({@code camera.neighbors}), eased, 0 to 1; 1 when it is not
     * flying over. Below 1 while the station left hears {@code onClose}: it can hand what floats in the air over to the next one,
     * which is {@link #focusedStation()} from then on, also before the server has let the player in.
     */
    public static float goingOver() {
        return FocusController.get().goingOver();
    }

    /** The station the local player works at. */
    public static Optional<StationView> focusedStation() {
        return Optional.ofNullable(FocusController.get().station());
    }

    /**
     * Buffers for a {@link NodePainter} that multiply an ARGB colour into what is drawn through them; an alpha below 255 fades it. Items
     * and models cannot be tinted otherwise. Block items (a carrot, a mushroom) ask for the block sheet's solid or cutout type, which
     * ignores alpha: through these buffers they get the see-through item type, so they fade like any other icon. Other models must
     * be drawn with a see-through render type to fade.
     */
    public static com.pockyl.diegesis.api.client.SceneBuffers tinted(com.pockyl.diegesis.api.client.SceneBuffers buffers, int argb) {
        boolean fading = argb >>> 24 < 255;
        return type -> new com.pockyl.diegesis.impl.client.render.TintedConsumer(buffers.getBuffer(fading ? seeThrough(type) : type), argb);
    }

    private static net.minecraft.client.renderer.rendertype.RenderType seeThrough(net.minecraft.client.renderer.rendertype.RenderType type) {
        if (type == net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet()) return net.minecraft.client.renderer.Sheets.translucentBlockItemSheet();

        return type;
    }

    /** A station shown on this client at {@code pos}. */
    public static Optional<StationView> station(BlockPos pos) {
        return Optional.ofNullable(ClientStations.get().at(pos));
    }
}
