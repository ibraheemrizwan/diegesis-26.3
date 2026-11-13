package com.pockyl.diegesis.api.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.StationSchema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A kind of in-world interface, bound to blocks: what happens when a player uses such a block. Create one with
 * {@link Diegesis#station(Identifier)} in common code, so the server and the client both know it.
 *
 * <pre>{@code
 * public static final StationType LAB_TABLE = Diegesis.station(MyMod.id("lab_table"))
 *         .block(MyBlocks.LAB_TABLE)   // a RegistryObject or any supplier: looked up when first needed
 *         .schema(StationSchema.builder().control("gas", ControlSpec.range(0, 1, 0)).build())
 *         .logic(LabTableLogic::new)
 *         .register();
 * }</pre>
 *
 * The scene (what is drawn and touched) is {@code assets/<namespace>/diegesis/scenes/<path>.json} for the station's id
 * unless {@link Builder#scene} names another.
 */
public final class StationType {
    private final Identifier id;
    private final Set<Block> blocks;
    private final List<Supplier<? extends Block>> lateBlocks;
    private volatile Set<Block> resolvedLateBlocks;
    private final Anchor anchor;
    private final List<TagKey<Block>> tags;
    private final Predicate<BlockState> condition;
    private final Authority authority;
    private final StationSchema schema;
    private final Identifier scene;
    private final StationLogic.Factory logic;
    private final OpenCondition openCondition;
    private final Orientation orientation;
    private final double reach;
    private final boolean shared;
    private final List<Seat> seats;
    private final boolean hidesBlock;
    private final boolean idleLogic;

    private StationType(Builder builder) {
        this.id = builder.id;
        this.blocks = Set.copyOf(builder.blocks);
        this.lateBlocks = List.copyOf(builder.lateBlocks);
        this.anchor = builder.anchor;
        this.tags = List.copyOf(builder.tags);
        this.condition = builder.condition;
        this.authority = builder.authority;
        this.schema = builder.schema;
        this.scene = builder.scene != null ? builder.scene : builder.id;
        this.logic = builder.logic;
        this.openCondition = builder.openCondition;
        this.orientation = builder.orientation;
        this.reach = builder.reach;
        this.shared = builder.shared;
        this.seats = List.copyOf(builder.seats);
        this.hidesBlock = builder.hidesBlock;
        this.idleLogic = builder.idleLogic;
    }

    /** Whether the scene stands in for the block's own model while the station is in use. */
    public boolean hidesBlock() {
        return hidesBlock;
    }

    /** Whether the logic also runs on clients while nobody works at the station ({@link Builder#idleLogic}). */
    public boolean idleLogic() {
        return idleLogic;
    }

    /** The places at the station, in the order they were added; empty when it has none. */
    public List<Seat> seats() {
        return seats;
    }

    /** The seat closest to a world position among those {@code free} lets through; empty when none is left. */
    public java.util.Optional<Seat> nearestSeat(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction facing,
            net.minecraft.world.phys.Vec3 player, Predicate<Seat> free) {
        Seat best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Seat seat : seats) {
            double distance = seat.worldPosition(pos, facing).distanceToSqr(player);
            if (distance < bestDistance && free.test(seat)) {
                best = seat;
                bestDistance = distance;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    public static Builder builder(Identifier id) {
        return new Builder(id);
    }

    public Identifier id() {
        return id;
    }

    /** Blocks matched directly (fast lookup); see also {@link #tags()} and {@link #lateBlocks()}. */
    public Set<Block> blocks() {
        return blocks;
    }

    /** Whether the station also names blocks through suppliers ({@link Builder#block(Supplier)}). */
    public boolean hasLateBlocks() {
        return !lateBlocks.isEmpty();
    }

    /** The blocks given as suppliers, looked up once they are all registered (empty until then). */
    public Set<Block> lateBlocks() {
        Set<Block> resolved = resolvedLateBlocks;
        if (resolved != null) {
            return resolved;
        }
        Set<Block> found = new LinkedHashSet<>();
        for (Supplier<? extends Block> supplier : lateBlocks) {
            Block block;
            try {
                block = supplier.get();
            } catch (RuntimeException notYet) {
                return Set.of();
            }
            if (block == null) {
                return Set.of();
            }
            found.add(block);
        }
        resolvedLateBlocks = Set.copyOf(found);
        return resolvedLateBlocks;
    }

    /** Where the station of a used block lives: the block itself, or the main block of a multiblock. */
    public Anchor anchor() {
        return anchor;
    }

    public List<TagKey<Block>> tags() {
        return tags;
    }

    /** Whether a block state belongs to this station. */
    public boolean matches(BlockState state) {
        boolean listed = blocks.contains(state.getBlock()) || tags.stream().anyMatch(state::is)
                || !lateBlocks.isEmpty() && lateBlocks().contains(state.getBlock());
        return listed && condition.test(state);
    }

    public Authority authority() {
        return authority;
    }

    public StationSchema schema() {
        return schema;
    }

    /** The scene id: {@code assets/<namespace>/diegesis/scenes/<path>.json}. */
    public Identifier scene() {
        return scene;
    }

    public StationLogic.Factory logic() {
        return logic;
    }

    public OpenCondition openCondition() {
        return openCondition;
    }

    public Orientation orientation() {
        return orientation;
    }

    /** How far (blocks, from the eyes to the block centre) a player may be to work at the station. */
    public double reach() {
        return reach;
    }

    /** Whether several players may work at the same block at once, sharing its state. */
    public boolean shared() {
        return shared;
    }

    @Override
    public String toString() {
        return "StationType[" + id + "]";
    }

    /** Decides whether using the block opens the station; otherwise the block's normal use happens. */
    @FunctionalInterface
    public interface OpenCondition {
        /** Like vanilla: sneaking with an item in hand uses the item instead of the block. */
        OpenCondition DEFAULT = (player, pos, state) -> !(player.isSecondaryUseActive()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()));

        /** Runs on the client when the block is used and again on the server; false leaves the block its normal use. */
        boolean test(Player player, BlockPos pos, BlockState state);
    }

    /**
     * Finds the block a station belongs to. A station spanning several blocks (a long table, a machine of four blocks)
     * lives at one main block: every part opens the same station there, and its scene is placed and turned from that
     * block (it may reach beyond it - scene coordinates past 16 pixels are the neighbouring blocks).
     */
    @FunctionalInterface
    public interface Anchor {
        /** Every block is its own station. */
        Anchor SELF = (level, pos, state) -> pos;

        /**
         * The main block's position for a use of the block at {@code pos}; {@code pos} itself for the main block. The
         * main block must match the station too.
         */
        BlockPos resolve(BlockGetter level, BlockPos pos, BlockState state);
    }

    /** How the scene is turned for a use of the station. */
    public enum Orientation {
        /** The block's facing property when it has one, otherwise towards the player who opens it. */
        AUTO,
        /** Always the block's facing property ({@code facing} or {@code horizontal_facing}); north when missing. */
        BLOCK,
        /** Towards the player who opens the station. */
        PLAYER,
        /** Never turned: the scene as authored, front to the north. */
        FIXED;

        /** The facing for a use of the station by a player standing in {@code playerDirection} from it. */
        public Direction resolve(BlockState state, Direction towardsPlayer) {
            Direction fromBlock = blockFacing(state);
            return switch (this) {
                case AUTO -> fromBlock != null ? fromBlock : towardsPlayer;
                case BLOCK -> fromBlock != null ? fromBlock : Direction.NORTH;
                case PLAYER -> towardsPlayer;
                case FIXED -> Direction.NORTH;
            };
        }

        private static Direction blockFacing(BlockState state) {
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
            if (state.hasProperty(BlockStateProperties.FACING)) {
                Direction facing = state.getValue(BlockStateProperties.FACING);
                return facing.getAxis().isHorizontal() ? facing : null;
            }
            return null;
        }
    }

    public static final class Builder {
        private boolean hidesBlock;
        private boolean idleLogic;
        private final Identifier id;
        private final Set<Block> blocks = new LinkedHashSet<>();
        private final List<Supplier<? extends Block>> lateBlocks = new java.util.ArrayList<>();
        private Anchor anchor = Anchor.SELF;
        private final List<TagKey<Block>> tags = new java.util.ArrayList<>();
        private Predicate<BlockState> condition = state -> true;
        private Authority authority = Authority.SERVER;
        private StationSchema schema = StationSchema.EMPTY;
        private Identifier scene;
        private StationLogic.Factory logic = station -> StationLogic.NONE;
        private OpenCondition openCondition = OpenCondition.DEFAULT;
        private Orientation orientation = Orientation.AUTO;
        private double reach = 6.0;
        private boolean shared = true;
        private final List<Seat> seats = new java.util.ArrayList<>();

        private Builder(Identifier id) {
            this.id = Objects.requireNonNull(id);
        }

        /** The blocks that open this station. */
        public Builder blocks(Block... values) {
            blocks.addAll(List.of(values));
            return this;
        }

        /**
         * A block that opens this station, looked up when it is first needed: for blocks registered after the station is
         * built (a Forge {@code RegistryObject}, a Fabric field filled in the mod initializer).
         */
        public Builder block(Supplier<? extends Block> value) {
            lateBlocks.add(Objects.requireNonNull(value));
            return this;
        }

        /** The main block of a multiblock station (see {@link Anchor}); every block is its own station by default. */
        public Builder anchor(Anchor value) {
            anchor = Objects.requireNonNull(value);
            return this;
        }

        /** Every block in a tag opens this station. */
        public Builder tag(TagKey<Block> tag) {
            tags.add(tag);
            return this;
        }

        /** Only block states passing {@code value} open the station (e.g. only the lower half of a tall block). */
        public Builder when(Predicate<BlockState> value) {
            condition = value;
            return this;
        }

        /** {@link Authority#SERVER} by default. */
        public Builder authority(Authority value) {
            authority = value;
            return this;
        }

        /** Shorthand for {@code authority(Authority.CLIENT)}. */
        public Builder clientOnly() {
            return authority(Authority.CLIENT);
        }

        public Builder schema(StationSchema value) {
            schema = value;
            return this;
        }

        /** Another scene than the one named like the station. */
        public Builder scene(Identifier value) {
            scene = value;
            return this;
        }

        public Builder logic(StationLogic.Factory value) {
            logic = value;
            return this;
        }

        public Builder openWhen(OpenCondition value) {
            openCondition = value;
            return this;
        }

        public Builder orientation(Orientation value) {
            orientation = value;
            return this;
        }

        public Builder reach(double blocks) {
            reach = blocks;
            return this;
        }

        /** One player at a time; others get the block's normal use while it is busy. */
        public Builder exclusive() {
            shared = false;
            return this;
        }

        /**
         * The block's own model is not drawn while the station is in use, for everyone who sees it: the scene shows the block
         * instead, so its parts can open and move. The block is back when the last player has left. Works on any block, also
         * another mod's; its block entity renderer, if it has one, goes on drawing.
         */
        public Builder hidesBlock() {
            hidesBlock = true;
            return this;
        }

        /**
         * Shows the block's real state in an {@code idle_visible} scene while nobody works at the station, with no traffic: every
         * client near it makes its own copy of the logic and runs {@link StationLogic#tick} on it. For stations on blocks whose block
         * entity is no {@code StationHost} but sends its content to clients anyway: the logic binds the slots as on the server, and
         * must only read and set the state there, it is no authority. Once somebody works at the station the server's state takes over.
         */
        public Builder idleLogic() {
            idleLogic = true;
            return this;
        }

        /**
         * A place at the station. A player who opens the station takes the free seat nearest to them and sees it through the
         * scene's {@code camera.seats} shot of that seat; with every seat taken they watch from the rig's own camera.
         */
        public Builder seat(Seat value) {
            if (seats.stream().anyMatch(seat -> seat.id().equals(value.id()))) {
                throw new IllegalArgumentException("Station " + id + " has two seats named " + value.id());
            }
            seats.add(value);
            return this;
        }

        public StationType build() {
            if (blocks.isEmpty() && tags.isEmpty() && lateBlocks.isEmpty()) {
                throw new IllegalStateException("Station " + id + " has no blocks");
            }
            if (authority == Authority.CLIENT && schema.hasItems()) {
                throw new IllegalStateException("Station " + id + " is client-only but has slots or outputs: items can only move"
                        + " between a player and a station through the server");
            }
            return new StationType(this);
        }

        /** Builds the station and registers it with {@link Diegesis#register}. */
        public StationType register() {
            return Diegesis.register(build());
        }
    }
}
