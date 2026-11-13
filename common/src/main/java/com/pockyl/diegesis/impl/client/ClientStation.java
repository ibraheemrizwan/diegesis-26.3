package com.pockyl.diegesis.impl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.client.NodeView;
import com.pockyl.diegesis.api.client.ParticleStyle;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.EmitterDefinition;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.api.state.StationHost;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.api.state.TankSpec;
import com.pockyl.diegesis.api.station.Authority;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.action.ActionHandler;
import com.pockyl.diegesis.impl.action.StationAction;
import com.pockyl.diegesis.impl.client.fx.PourStreams;
import com.pockyl.diegesis.impl.client.fx.SceneParticles;
import com.pockyl.diegesis.impl.client.focus.FocusInput;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;
import com.pockyl.diegesis.impl.client.scene.SceneInstance;
import com.pockyl.diegesis.impl.client.scene.SceneLibrary;
import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.impl.network.Presence;
import com.pockyl.diegesis.platform.ClientPlatform;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A station shown on this client: in use by the local player, watched while someone nearby uses it, or idle (a block
 * entity whose scene stays visible). Holds the client's copy of the state, the scene's nodes and the behaviour. For a
 * client-only station it also runs the station logic, so it is the {@link StationContext} too.
 */
public final class ClientStation implements StationView, StationContext {
    private final StationType type;
    private final ClientLevel level;
    private final BlockPos pos;
    private Direction facing;
    private final StationState state;
    private final boolean hostState;
    private final StationLogic logic;
    /** The logic's copy on this client, for what the block shows while nobody works at it ({@code idleLogic}). */
    private final StationLogic idleLogic;
    private StationBehavior behavior;
    private SceneInstance scene;
    private final Map<UUID, RemoteHolding> remote = new HashMap<>();
    private final SceneParticles particles = new SceneParticles();
    private final PourStreams pourStreams = new PourStreams();
    private final Map<String, com.pockyl.diegesis.impl.client.fx.StationLoop> loops = new HashMap<>();
    private static final java.util.Set<Identifier> WARNED_PARTICLES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Matrix4f toWorldMatrix = new Matrix4f();
    private float age;
    private boolean focused;
    /** Whether the local player came by using the block, not over from a neighbour. */
    private boolean openedByUse;
    private Predicate<ItemStack> inventoryFilter;
    private boolean carriedHidden;
    private boolean inSession;
    private String seat;
    private Component caption;
    private long captionStart;
    private long captionEnd;

    ClientStation(StationType type, ClientLevel level, BlockPos pos, Direction facing) {
        this.type = type;
        this.level = level;
        this.pos = pos.immutable();
        this.facing = facing;
        StationState shared = null;
        if (level.getBlockEntity(pos) instanceof StationHost host && host.stationState().schema() == type.schema()) {
            // The block entity's copy is kept up to date by its own update packets; show it while idle.
            shared = host.stationState();
        }
        this.hostState = shared != null;
        this.state = shared != null ? shared : new StationState(type.schema());
        this.logic = type.authority() == Authority.CLIENT ? type.logic().create(this) : StationLogic.NONE;
        this.idleLogic = type.authority() == Authority.SERVER && type.idleLogic() ? startIdleLogic() : StationLogic.NONE;
        rebuildScene();
        this.behavior = ClientRegistry.behavior(type.id()).map(factory -> safeCreate(factory)).orElse(new StationBehavior() {
        });
        sceneReady();
    }

    private StationLogic startIdleLogic() {
        try {
            StationLogic idle = type.logic().create(this);
            idle.tick(this);
            state.pollStorages();
            return idle;
        } catch (RuntimeException e) {
            DiegesisMod.LOGGER.error("Idle logic of station {} at {} failed to start", type.id(), pos, e);
            return StationLogic.NONE;
        }
    }

    private StationBehavior safeCreate(StationBehavior.Factory factory) {
        try {
            return factory.create(this);
        } catch (RuntimeException e) {
            DiegesisMod.LOGGER.error("Behaviour of station {} failed to start", type.id(), e);
            return new StationBehavior() {
            };
        }
    }

    private void sceneReady() {
        if (scene != null && behavior != null) {
            fire(() -> behavior.onSceneReady(this));
        }
    }

    /** Rebuilds the nodes from the current scene file (after F3+T). */
    void rebuildScene() {
        SceneDefinition definition = SceneLibrary.INSTANCE.scene(type.scene());
        if (definition == null) {
            if (scene != null || age == 0.0F) {
                DiegesisMod.LOGGER.warn("Station {} has no scene {} (assets/{}/diegesis/scenes/{}.json)", type.id(), type.scene(),
                        type.scene().getNamespace(), type.scene().getPath());
            }
            scene = null;
            return;
        }
        scene = new SceneInstance(this, definition);
        scene.update(state, 0.0F);
        sceneReady();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // StationView / StationContext
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public StationType type() {
        return type;
    }

    @Override
    public Level level() {
        return level;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    @Override
    public BlockState blockState() {
        return level.getBlockState(pos);
    }

    @Override
    public Direction facing() {
        return facing;
    }

    void setFacing(Direction value) {
        if (value != facing) {
            facing = value;
        }
    }

    @Override
    public StationState state() {
        return state;
    }

    @Override
    public Collection<? extends Player> users() {
        Player player = Minecraft.getInstance().player;
        return focused && player != null ? List.of(player) : List.of();
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public boolean isInUse() {
        return isInSession();
    }

    @Override
    public float age() {
        return age;
    }

    @Override
    public Optional<String> seat() {
        return Optional.ofNullable(seat);
    }

    @Override
    public Optional<String> seatOf(Player player) {
        return player == Minecraft.getInstance().player ? seat() : Optional.empty();
    }

    /** Which seat the local player holds: guessed when they use the block, then what the server says. */
    public void setSeat(String value) {
        seat = value == null || value.isEmpty() ? null : value;
    }

    @Override
    public void showCaption(Component text, float seconds) {
        long now = net.minecraft.util.Util.getMillis();
        if (text == null || !text.equals(caption)) {
            captionStart = now;
        }
        caption = text;
        captionEnd = seconds > 0.0F ? now + (long) (seconds * 1000.0F) : Long.MAX_VALUE;
    }

    /** The line to show now, or null. */
    public Component caption() {
        return caption != null && net.minecraft.util.Util.getMillis() < captionEnd ? caption : null;
    }

    /** How solid the caption is now, 0 to 1: it fades in and out. */
    public float captionAlpha() {
        long now = net.minecraft.util.Util.getMillis();
        float in = (now - captionStart) / 200.0F;
        float out = captionEnd == Long.MAX_VALUE ? 1.0F : (captionEnd - now) / 300.0F;
        return Mth.clamp(Math.min(in, out), 0.0F, 1.0F);
    }

    @Override
    public Optional<NodeView> node(String id) {
        return scene == null ? Optional.empty() : Optional.ofNullable(scene.node(id));
    }

    @Override
    public Vec3 toWorld(float x, float y, float z) {
        Vector3f point = stationToWorld(toWorldMatrix).transformPosition(new Vector3f(x, y, z));
        return new Vec3(pos.getX() + point.x, pos.getY() + point.y, pos.getZ() + point.z);
    }

    @Override
    public void sendAction(String action, CompoundTag data) {
        if (focused) {
            perform(new StationAction.Custom(action, data.copy()));
        }
    }

    @Override
    public void spawnParticles(Identifier particle, String node, int count, int color) {
        ParticleStyle style = style(particle);
        if (style == null) {
            return;
        }
        Vector3f at = new Vector3f(8.0F, scene == null ? 16.0F : scene.definition().workPlane(), 8.0F);
        if (node != null && scene != null && scene.node(node) != null) {
            scene.node(node).matrix().getTranslation(at);
        }
        // Falling kinds (sparks, drops) burst upwards and arc; light ones drift.
        Vector3f velocity = new Vector3f(0.0F, style.gravity() > 20.0F ? 16.0F : 2.0F, 0.0F);
        particles.spawn(style, at, velocity, 0.4F, count, color);
    }

    @Override
    public void spawnParticles(Identifier particle, Vector3fc at, Vector3fc velocity, float spread, int count, int color) {
        ParticleStyle style = style(particle);
        if (style != null) {
            particles.spawn(style, at, velocity, spread, count, color);
        }
    }

    private static ParticleStyle style(Identifier particle) {
        ParticleStyle style = ClientRegistry.particle(particle);
        if (style == null && WARNED_PARTICLES.add(particle)) {
            DiegesisMod.LOGGER.warn("Unknown scene particle {}: register it with DiegesisClient.registerParticle", particle);
        }
        return style;
    }

    public SceneParticles particles() {
        return particles;
    }

    public PourStreams pourStreams() {
        return pourStreams;
    }

    @Override
    public void setInventoryFilter(Predicate<ItemStack> filter) {
        inventoryFilter = filter;
    }

    @Override
    public void setCarriedHidden(boolean hidden) {
        carriedHidden = hidden;
    }

    @Override
    public void holdStroke() {
        if (focused) {
            FocusInput.get().holdStroke(this);
        }
    }

    @Override
    public boolean openedByUse() {
        return focused && openedByUse;
    }

    /** Set by the focus as the player comes: by using the block, or over from a neighbour. */
    public void setOpenedByUse(boolean value) {
        openedByUse = value;
    }

    @Override
    public void takeFromInventory(int slot) {
        if (focused && slot >= 0 && slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) {
            perform(new StationAction.Click(new StationAction.Target.Inventory(slot), 0, StationAction.ClickKind.PICKUP));
        }
    }

    @Override
    public void putAwayCarried() {
        Player player = Minecraft.getInstance().player;
        if (focused && player != null && !player.inventoryMenu.getCarried().isEmpty()
                && perform(new StationAction.Click(new StationAction.Target.Inventory(0), 1, StationAction.ClickKind.PUT_AWAY))) {
            com.pockyl.diegesis.impl.client.focus.UiSounds.putDown(net.minecraft.world.phys.Vec3.atCenterOf(pos()));
        }
    }

    /** Whether the station draws the stack on the cursor itself, while the local player works here. */
    public boolean isCarriedHidden() {
        return carriedHidden && focused;
    }

    /** What a compact drawer shows: never null. */
    public Predicate<ItemStack> inventoryFilter() {
        return inventoryFilter == null ? stack -> true : inventoryFilter;
    }

    @Override
    public void setInventoryOpen(boolean open) {
        if (focused) {
            FocusInput.get().setInventoryOpen(open);
        }
    }

    @Override
    public boolean isInventoryOpen() {
        return focused && FocusInput.get().isInventoryOpen();
    }

    @Override
    public boolean glance(String id) {
        return focused && FocusInput.get().glance(id);
    }

    @Override
    public void glanceBack() {
        if (focused) {
            FocusInput.get().glanceBack();
        }
    }

    @Override
    public Optional<String> currentGlance() {
        return focused ? Optional.ofNullable(FocusInput.get().currentGlance()) : Optional.empty();
    }

    @Override
    public void showOverlay(com.pockyl.diegesis.api.client.ScreenOverlay overlay) {
        if (focused) {
            com.pockyl.diegesis.impl.client.focus.FocusScreen.showOverlay(overlay);
        }
    }

    @Override
    public void loopSound(String name, SoundEvent sound, float volume, float pitch) {
        com.pockyl.diegesis.impl.client.fx.StationLoop loop = loops.get(name);
        if (loop == null || loop.isStopped()) {
            if (volume <= 0.0F) {
                return;
            }
            Vec3 at = Vec3.atCenterOf(pos);
            loop = new com.pockyl.diegesis.impl.client.fx.StationLoop(sound, at.x, at.y, at.z, pitch, () -> ClientStations.get().at(pos) == this);
            loops.put(name, loop);
            Minecraft.getInstance().getSoundManager().play(loop);
        }
        loop.want(volume, pitch);
    }

    @Override
    public void playSound(SoundEvent sound, String node, float volume, float pitch) {
        Vec3 at = node == null ? Vec3.atCenterOf(pos) : node(node).map(NodeView::worldPosition).orElse(Vec3.atCenterOf(pos));
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.BLOCKS, volume, pitch,
                level.getRandom(), at.x, at.y, at.z));
    }

    @Override
    public void effect(Identifier effect, String node, CompoundTag data) {
        playEffect(effect, node == null || node.isEmpty() ? null : node, data);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Library side
    // ---------------------------------------------------------------------------------------------------------------

    public SceneInstance scene() {
        return scene;
    }

    public StationBehavior behavior() {
        return behavior;
    }

    public boolean isInSession() {
        return inSession || focused;
    }

    void setInSession(boolean value) {
        inSession = value;
    }

    boolean hasHostState() {
        return hostState;
    }

    /** Whether the station should still be drawn although nobody uses it. */
    boolean isIdleVisible() {
        return scene != null && scene.definition().idleVisible() && level.getBlockEntity(pos) != null;
    }

    public void setFocusedInternal(boolean value) {
        if (value == focused) {
            return;
        }
        focused = value;
        if (!value) {
            carriedHidden = false;
            // The server tells nobody who leaves by themselves; if others stay there, its next update brings the session back.
            inSession = false;
        }
        Player player = Minecraft.getInstance().player;
        if (value) {
            if (type.authority() == Authority.CLIENT && player != null) {
                logic.updateOutputs(this);
                logic.onOpen(this, player);
            }
            fire(() -> behavior.onOpen(this));
        } else {
            if (type.authority() == Authority.CLIENT && player != null) {
                logic.onClose(this, player);
            }
            fire(() -> behavior.onClose(this));
        }
    }

    /**
     * Station space (pixels, front to the north) to block-relative world space: turned about the block centre to the
     * facing, like a block model rotated in a blockstate file.
     */
    public Matrix4f stationToWorld(Matrix4f dest) {
        float yaw = switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
        return dest.identity().translate(0.5F, 0.0F, 0.5F).rotateY((float) Math.toRadians(-yaw)).translate(-0.5F, 0.0F, -0.5F)
                .scale(1.0F / 16.0F);
    }

    /** Applies a player action: directly for a client-only station, predicted and sent for a server one. */
    /**
     * Applies a player action: directly for a client-only station, predicted and sent for a server one. Returns
     * whether it (probably) did something - for feedback such as sounds; the server stays the judge.
     */
    public boolean perform(StationAction action) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (type.authority() == Authority.CLIENT) {
            return !ActionHandler.movesItems(action) && ActionHandler.apply(this, logic, player, action);
        }
        boolean done = true;
        if (action instanceof StationAction.Click click && click.target() instanceof StationAction.Target.Output output) {
            // Outputs need the server's logic: only guess that something is there to take.
            done = !state.getOutput(output.output()).isEmpty();
        } else if (action instanceof StationAction.Click click && click.kind() == StationAction.ClickKind.PUT_AWAY) {
            // Never guessed: the slot it goes back to is remembered by the server. A guess from the client's own memory put a knife
            // into a slot the server never filled, and the server, seeing no change there, never took that phantom back.
            done = !player.inventoryMenu.getCarried().isEmpty();
        } else if (!(action instanceof StationAction.Custom) && !(action instanceof StationAction.Press)) {
            // Show the result at once; the server's answer confirms or corrects it.
            done = ActionHandler.apply(this, StationLogic.NONE, player, action);
        }
        ClientPlatform.INSTANCE.sendToServer(new Packets.Action(pos, action));
        return done;
    }

    void tick() {
        if (type.authority() == Authority.CLIENT && focused) {
            fire(() -> logic.tick(this));
        }
        if (idleLogic != StationLogic.NONE && !isInSession()) {
            fire(() -> {
                idleLogic.tick(this);
                state.pollStorages();
            });
        }
        fire(() -> behavior.tick(this));
    }

    /** Advances animations by {@code dt} seconds; called once per frame before drawing. */
    public void frame(float dt) {
        age += dt;
        StationState.Changes changes = state.takeChanges();
        if (!changes.isEmpty()) {
            fire(() -> behavior.onStateChanged(this, changes));
        }
        fire(() -> behavior.frame(this, dt));
        for (RemoteHolding holding : remote.values()) {
            holding.update(dt);
        }
        if (scene != null) {
            scene.update(state, dt);
            emit(dt);
            feedRemoteStreams();
            pourStreams.update(dt, this, scene.definition().pouring());
        }
        particles.update(dt);
    }

    /** What other players pour runs here too. */
    private void feedRemoteStreams() {
        for (RemoteHolding holding : remote.values()) {
            NodeInstance held = holding.node == null ? null : scene.node(holding.node);
            NodeInstance source = held == null ? null : held.pourer();
            NodeInstance target = holding.pourInto.isEmpty() ? null : scene.node(holding.pourInto);
            if (source != null && target != null && target.liquid() != null && holding.flow > 0.0F) {
                pourStreams.feed(source, target, holding.flow);
            }
        }
    }

    /** Lets every switched-on emitter give off its particles for this frame. */
    private void emit(float dt) {
        burnPlaced(dt);
        for (NodeInstance node : scene.nodes()) {
            if (!node.isEmitting() || !node.isVisible()) {
                continue;
            }
            EmitterDefinition emitter = node.definition().emitter().get();
            int due = node.emitDue(dt, node.emitRate());
            if (due <= 0) {
                continue;
            }
            ParticleStyle style = style(emitter.particle());
            if (style == null) {
                continue;
            }
            if (emitter.inLiquid()) {
                emitInLiquid(node, emitter, style, due);
                continue;
            }
            Vector3f at = node.matrix().transformPosition(new Vector3f(emitter.offset()));
            // The velocity turns with the node but keeps its length: a scaled-down node must not slow its particles.
            Vector3f velocity = new Vector3f(emitter.velocity());
            float speed = velocity.length();
            if (speed > 0.0F) {
                node.matrix().transformDirection(velocity).normalize().mul(speed);
            }
            particles.spawn(style, at, velocity, emitter.spread(), due, emitter.color().orElse(0), emitter.scale());
        }
    }

    /** Candles and torches standing in the scene burn. */
    private void burnPlaced(float dt) {
        ParticleStyle flame = style(com.pockyl.diegesis.api.client.DiegesisParticles.FLAME);
        for (NodeInstance node : scene.placedNodes()) {
            if (flame != null && node.placed().burns(dt)) {
                particles.spawn(flame, node.placed().flamePosition(), new Vector3f(0.0F, 0.15F, 0.0F), 0.05F, 1, 0, 1.3F);
            }
        }
    }

    @Override
    public void spawnInLiquid(Identifier particle, String node, int count, int color, Vector3fc velocity) {
        ParticleStyle style = style(particle);
        NodeInstance instance = scene == null || node == null ? null : scene.node(node);
        if (style != null && instance != null) {
            inLiquid(instance, style, count, color, velocity, 1.0F);
        }
    }

    @Override
    public void spawnOnLiquid(Identifier particle, String node, int count, int color, Vector3fc velocity) {
        ParticleStyle style = style(particle);
        NodeInstance instance = scene == null || node == null ? null : scene.node(node);
        TankBinding binding = instance == null ? null : instance.definition().tank().orElse(null);
        if (style != null && binding != null && !state.getTank(binding.tank()).isEmpty()) {
            particles.spawnOnLiquid(style, instance.matrix(), binding.inner(), surfaceOf(instance, binding), velocity, count, color, 1.0F);
        }
    }

    @Override
    public void spawnAtLiquidEdge(Identifier particle, String node, int count, int color, float scale) {
        ParticleStyle style = style(particle);
        NodeInstance instance = scene == null || node == null ? null : scene.node(node);
        TankBinding binding = instance == null ? null : instance.definition().tank().orElse(null);
        if (style != null && binding != null && !state.getTank(binding.tank()).isEmpty()) {
            particles.spawnAtEdge(style, instance.matrix(), binding.inner(), surfaceOf(instance, binding), count, color, scale, instance.liquid());
        }
    }

    /** The height of a node's liquid now, in its pixels. */
    private static float surfaceOf(NodeInstance node, TankBinding binding) {
        Box inner = binding.inner();
        return inner.from().y() + (inner.to().y() - inner.from().y()) * node.liquid().fraction();
    }

    @Override
    public Optional<Vector3f> cursorOn(float height) {
        return focused ? FocusInput.get().cursorOn(this, height) : Optional.empty();
    }

    @Override
    public Optional<Vector3f> screenOn(float x, float y, float height) {
        return focused ? FocusInput.get().screenOn(this, x, y, height) : Optional.empty();
    }

    @Override
    public Optional<Vector3f> pointerOn(String node, float height) {
        NodeInstance instance = scene == null ? null : scene.node(node);
        return focused && instance != null ? FocusInput.get().pointerOn(this, instance, height) : Optional.empty();
    }

    /** Offers a click with a stack on the cursor to the behaviour; true when it took it. */
    public boolean inventoryShiftClick(int slot, ItemStack stack) {
        try {
            return behavior.onInventoryShiftClick(this, slot, stack);
        } catch (RuntimeException e) {
            DiegesisMod.LOGGER.error("Client code of station {} at {} failed", type.id(), pos, e);
            return false;
        }
    }

    public boolean itemClick(String node, ItemStack stack, int button, boolean shift) {
        try {
            return behavior.onItemClick(this, node, stack, button, shift);
        } catch (RuntimeException e) {
            DiegesisMod.LOGGER.error("Client code of station {} at {} failed", type.id(), pos, e);
            return false;
        }
    }

    /** Bubbles and the like: anywhere in the node's liquid, rising to its surface. */
    private void emitInLiquid(NodeInstance node, EmitterDefinition emitter, ParticleStyle style, int due) {
        inLiquid(node, style, due, emitter.color().orElse(0), emitter.velocity(), emitter.scale());
    }

    private void inLiquid(NodeInstance node, ParticleStyle style, int count, int color, Vector3fc velocity, float scale) {
        TankBinding binding = node.definition().tank().orElse(null);
        TankSpec spec = binding == null ? null : type.schema().tanks().get(binding.tank());
        if (spec == null) {
            return;
        }
        TankContents contents = state.getTank(binding.tank());
        if (contents.isEmpty()) {
            return;
        }
        particles.spawnInLiquid(style, node.matrix(), binding.inner(), surfaceOf(node, binding), velocity, count, color, scale, node.liquid());
    }

    public void onEvent(SceneEvent event) {
        fire(() -> behavior.onEvent(this, event));
    }

    void playEffect(Identifier effect, String node, CompoundTag data) {
        ClientRegistry.effect(effect).ifPresent(handler -> fire(() -> handler.play(this, node, data)));
        fire(() -> behavior.onEffect(this, effect, node, data));
    }

    /** What another player at this station holds now. */
    void setRemote(UUID player, Presence presence) {
        RemoteHolding previous = remote.remove(player);
        if (previous != null && previous.node != null && scene != null && (presence.isEmpty() || !previous.node.equals(presence.node()))) {
            NodeInstance node = scene.node(previous.node);
            if (node != null) {
                node.showRemoteCarry(null);
                node.setTilt(new Vector3f(1.0F, 0.0F, 0.0F), 0.0F, 0.0F);
            }
        }
        if (presence.isEmpty()) {
            return;
        }
        RemoteHolding holding = previous != null && previous.matches(presence) ? previous : new RemoteHolding(presence);
        holding.target.set(presence.pos());
        holding.rotation.set(presence.rotation());
        holding.pourInto = presence.pourInto();
        holding.flow = presence.flow();
        remote.put(player, holding);
        if (holding.node != null && scene != null) {
            NodeInstance node = scene.node(holding.node);
            if (node != null && !node.isHeld()) {
                node.showRemoteCarry(presence.pos());
                node.setTilt(presence.tilt(), presence.tilt().length(), presence.turn());
            }
        }
    }

    /** Items other players carry over this station, for the renderer. */
    public Collection<RemoteHolding> remoteHoldings() {
        return remote.values();
    }

    void clearRemote() {
        for (UUID player : List.copyOf(remote.keySet())) {
            setRemote(player, Presence.NONE);
        }
    }

    private void fire(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            DiegesisMod.LOGGER.error("Client code of station {} at {} failed", type.id(), pos, e);
        }
    }

    /** An item or node another player carries, smoothed between their updates. */
    public static final class RemoteHolding {
        private final String node;
        private final ItemStack item;
        private final Vector3f target = new Vector3f();
        private final Vector3f rotation = new Vector3f();
        private final Vector3f shown = new Vector3f();
        private boolean placed;
        private String pourInto = "";
        private float flow;

        RemoteHolding(Presence presence) {
            this.node = presence.node().isEmpty() ? null : presence.node();
            this.item = presence.carried();
        }

        boolean matches(Presence presence) {
            return (node == null ? presence.node().isEmpty() : node.equals(presence.node())) && ItemStack.matches(item, presence.carried());
        }

        void update(float dt) {
            if (!placed) {
                shown.set(target);
                placed = true;
            }
            float k = 1.0F - (float) Math.pow(0.5, dt / 0.06F);
            shown.lerp(target, k);
        }

        /** The carried item, empty when the player carries a node instead. */
        public ItemStack item() {
            return item;
        }

        public Vector3fc position() {
            return shown;
        }

        /** The lean of the carried thing, degrees about x and z. */
        public Vector3fc rotation() {
            return rotation;
        }
    }
}
