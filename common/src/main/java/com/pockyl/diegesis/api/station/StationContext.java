package com.pockyl.diegesis.api.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.api.state.StationState;

import java.util.Collection;

/**
 * A station in use, as its {@link StationLogic} sees it: on the server for {@link Authority#SERVER} stations, on the
 * client for {@link Authority#CLIENT} ones.
 */
public interface StationContext {
    StationType type();

    Level level();

    BlockPos pos();

    BlockState blockState();

    /** The direction the station's front faces for this use; the scene is turned to it. */
    Direction facing();

    StationState state();

    /** The players working at the station now. */
    Collection<? extends Player> users();

    /** The seat a user holds ({@code StationType.Builder.seat}); empty for someone without one. */
    default java.util.Optional<String> seatOf(Player player) {
        return java.util.Optional.empty();
    }

    /** The user holding a seat; empty while it is free. */
    default java.util.Optional<? extends Player> playerAt(String seat) {
        return users().stream().filter(player -> seatOf(player).filter(seat::equals).isPresent()).findFirst();
    }

    /** The block entity at the station, if the block has one. */
    default BlockEntity blockEntity() {
        return level().getBlockEntity(pos());
    }

    default boolean isClientSide() {
        return level().isClientSide();
    }

    /**
     * Plays an effect at the station for everyone who can see it: the clients run the effect registered under
     * {@code effect} (each built-in particle style of {@code DiegesisParticles} is also an effect: a burst at the node)
     * and the station's client behaviour hears about it.
     *
     * @param node the node the effect starts at, or {@code null} for the station's centre
     * @param data extra parameters for the effect
     */
    void effect(Identifier effect, String node, CompoundTag data);

    default void effect(Identifier effect, String node) {
        effect(effect, node, new CompoundTag());
    }
}
