package com.pockyl.diegesis.impl.client;

import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.api.client.DiegesisParticles;
import com.pockyl.diegesis.api.client.EffectHandler;
import com.pockyl.diegesis.api.client.LiquidLook;
import com.pockyl.diegesis.api.client.ParticleStyle;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.scene.SceneDefinition;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What client code registered: behaviours, code-built scenes, extra models, effects. */
public final class ClientRegistry {
    private static final Map<Identifier, StationBehavior.Factory> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Map<Identifier, SceneDefinition> SCENES = new ConcurrentHashMap<>();
    private static final Set<Identifier> MODELS = ConcurrentHashMap.newKeySet();
    private static final Map<Identifier, EffectHandler> EFFECTS = new ConcurrentHashMap<>();
    private static final Map<Identifier, ParticleStyle> PARTICLES = new ConcurrentHashMap<>(DiegesisParticles.builtIn());
    private static final Map<Identifier, LiquidLook> LIQUIDS = new ConcurrentHashMap<>();
    /** Liquids nobody described: grey water. */
    private static final LiquidLook UNKNOWN_LIQUID = LiquidLook.of(0xB0A0A0A0);

    static {
        LIQUIDS.put(Identifier.withDefaultNamespace("water"), LiquidLook.of(0xD03F76E4));
        LIQUIDS.put(Identifier.withDefaultNamespace("lava"), new LiquidLook(0xFFFFFFFF, LiquidLook.LAVA_SPRITE, true));
        LIQUIDS.put(Identifier.withDefaultNamespace("milk"), LiquidLook.of(0xF0F4F1E8));
        // Every built-in particle is also an effect: a burst at the node (data: "count", "color").
        PARTICLES.keySet().forEach(id -> EFFECTS.put(id, (view, node, data) -> view.spawnParticles(id, node,
                data.contains("count") ? data.getIntOr("count", 0) : 12, data.getIntOr("color", 0))));
    }

    private ClientRegistry() {
    }

    public static void registerParticle(Identifier id, ParticleStyle style) {
        if (PARTICLES.putIfAbsent(id, style) != null) {
            throw new IllegalStateException("Particle " + id + " is registered twice");
        }
    }

    public static void registerLiquid(Identifier id, LiquidLook look) {
        LIQUIDS.put(id, look);
    }

    public static ParticleStyle particle(Identifier id) {
        return PARTICLES.get(id);
    }

    public static LiquidLook liquid(Identifier id) {
        return id == null ? UNKNOWN_LIQUID : LIQUIDS.getOrDefault(id, UNKNOWN_LIQUID);
    }

    public static void registerBehavior(Identifier station, StationBehavior.Factory factory) {
        if (BEHAVIORS.putIfAbsent(station, factory) != null) {
            throw new IllegalStateException("Station " + station + " already has a behaviour");
        }
    }

    public static void registerScene(Identifier id, SceneDefinition scene) {
        SCENES.put(id, scene);
    }

    public static void registerModel(Identifier model) {
        MODELS.add(model);
    }

    public static void registerEffect(Identifier id, EffectHandler handler) {
        if (EFFECTS.putIfAbsent(id, handler) != null) {
            throw new IllegalStateException("Effect " + id + " is registered twice");
        }
    }

    public static Optional<StationBehavior.Factory> behavior(Identifier station) {
        return Optional.ofNullable(BEHAVIORS.get(station));
    }

    public static Optional<SceneDefinition> codeScene(Identifier id) {
        return Optional.ofNullable(SCENES.get(id));
    }

    public static Set<Identifier> extraModels() {
        return Set.copyOf(MODELS);
    }

    public static Optional<EffectHandler> effect(Identifier id) {
        return Optional.ofNullable(EFFECTS.get(id));
    }
}
