package com.pockyl.diegesis.impl;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.station.StationType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Every registered station type, looked up by id and by block state. */
public final class StationRegistry {
    private static final Map<Identifier, StationType> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Block, List<StationType>> BY_BLOCK = new ConcurrentHashMap<>();
    private static final List<StationType> BY_TAG = new CopyOnWriteArrayList<>();

    private StationRegistry() {
    }

    public static StationType register(StationType type) {
        StationType previous = BY_ID.putIfAbsent(type.id(), type);
        if (previous != null) {
            throw new IllegalStateException("Station " + type.id() + " is registered twice");
        }
        type.blocks().forEach(block -> BY_BLOCK.computeIfAbsent(block, key -> new CopyOnWriteArrayList<>()).add(type));
        if (!type.tags().isEmpty() || type.hasLateBlocks()) {
            BY_TAG.add(type);
        }
        DiegesisMod.LOGGER.debug("Registered station {}", type.id());
        return type;
    }

    public static Optional<StationType> byId(Identifier id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** The first station that matches the block state: stations listing the block directly win over tag matches. */
    public static Optional<StationType> find(BlockState state) {
        List<StationType> direct = BY_BLOCK.get(state.getBlock());
        if (direct != null) {
            for (StationType type : direct) {
                if (type.matches(state)) {
                    return Optional.of(type);
                }
            }
        }
        for (StationType type : BY_TAG) {
            if (type.matches(state)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static boolean isEmpty() {
        return BY_ID.isEmpty();
    }

    public static Collection<StationType> all() {
        return BY_ID.values();
    }
}
