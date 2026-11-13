package com.pockyl.diegesis.impl.action;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Something a player does at a station, sent by the client and checked and applied where the state lives. Item moves
 * follow vanilla container screens: a stack is carried on the cursor ({@code player.inventoryMenu} carried stack), and
 * clicks pick up, put down, split, swap, shift-move, gather and spread it.
 */
public sealed interface StationAction {
    int MAX_NAME = 64;
    /** At most this many slots in one spread (a drag over slots). */
    int MAX_SPREAD = 64;

    void write(FriendlyByteBuf buf);

    static StationAction read(FriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case 0 -> new Click(Target.read(buf), buf.readByte(), buf.readEnum(ClickKind.class));
            case 1 -> {
                int count = Math.min(buf.readVarInt(), MAX_SPREAD);
                List<Target> targets = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    targets.add(Target.read(buf));
                }
                yield new Spread(targets, buf.readByte());
            }
            case 4 -> new SetControl(buf.readUtf(MAX_NAME), buf.readFloat());
            case 5 -> new Press(buf.readUtf(MAX_NAME));
            case 6 -> new Custom(buf.readUtf(MAX_NAME), Objects.requireNonNullElseGet(buf.readNbt(), CompoundTag::new));
            case 7 -> new Pour(buf.readUtf(MAX_NAME), buf.readUtf(MAX_NAME), buf.readVarInt());
            case 8 -> new Place(new SlotRef(buf.readUtf(MAX_NAME), buf.readVarInt()), ItemPose.read(buf));
            case 9 -> new Pose(new SlotRef(buf.readUtf(MAX_NAME), buf.readVarInt()), ItemPose.read(buf));
            default -> throw new IllegalArgumentException("Unknown station action " + kind);
        };
    }

    /** How a click acts, as in vanilla container screens. */
    enum ClickKind {
        /** Left: pick up / put down / swap the whole stack; right: pick up half / put down one. */
        PICKUP,
        /** Shift-click: moves the stack between the station and the inventory. */
        QUICK_MOVE,
        /** Double click: gathers matching items onto the cursor. */
        PICKUP_ALL,
        /** A right click nothing takes: the carried stack goes back into the inventory; the target does not matter. */
        PUT_AWAY
    }

    /** A place an item can be clicked: a station slot, an inventory slot or a station output. */
    sealed interface Target {
        void write(FriendlyByteBuf buf);

        static Target read(FriendlyByteBuf buf) {
            int type = buf.readByte();
            return switch (type) {
                case 0 -> new Station(new SlotRef(buf.readUtf(MAX_NAME), buf.readVarInt()));
                case 1 -> new Inventory(buf.readVarInt());
                case 2 -> new Output(buf.readUtf(MAX_NAME));
                default -> throw new IllegalArgumentException("Unknown click target " + type);
            };
        }

        record Station(SlotRef slot) implements Target {
            @Override
            public void write(FriendlyByteBuf buf) {
                buf.writeByte(0);
                buf.writeUtf(slot.group(), MAX_NAME);
                buf.writeVarInt(slot.index());
            }
        }

        /** An index of the player's {@code Inventory}: 0-8 hotbar, 9-35 main. */
        record Inventory(int index) implements Target {
            @Override
            public void write(FriendlyByteBuf buf) {
                buf.writeByte(1);
                buf.writeVarInt(index);
            }
        }

        record Output(String output) implements Target {
            @Override
            public void write(FriendlyByteBuf buf) {
                buf.writeByte(2);
                buf.writeUtf(output, MAX_NAME);
            }
        }
    }

    /** {@code button}: 0 left, 1 right. */
    record Click(Target target, int button, ClickKind kind) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(0);
            target.write(buf);
            buf.writeByte(button);
            buf.writeEnum(kind);
        }
    }

    /** The carried stack dragged over several slots: split evenly (left) or one each (right). */
    record Spread(List<Target> targets, int button) implements StationAction {
        public Spread {
            targets = List.copyOf(targets);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(1);
            buf.writeVarInt(targets.size());
            targets.forEach(target -> target.write(buf));
            buf.writeByte(button);
        }
    }

    record SetControl(String control, float value) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(4);
            buf.writeUtf(control, MAX_NAME);
            buf.writeFloat(value);
        }
    }

    record Press(String control) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(5);
            buf.writeUtf(control, MAX_NAME);
        }
    }

    /** Pours up to {@code amount} millibuckets from one tank into another (the server limits the pace). */
    record Pour(String from, String to, int amount) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(7);
            buf.writeUtf(from, MAX_NAME);
            buf.writeUtf(to, MAX_NAME);
            buf.writeVarInt(amount);
        }
    }

    /** Puts the stack on the cursor down freely, into an empty slot of a placed group: as much of it as the slot takes. */
    record Place(SlotRef slot, ItemPose pose) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(8);
            buf.writeUtf(slot.group(), MAX_NAME);
            buf.writeVarInt(slot.index());
            pose.write(buf);
        }
    }

    /** Turns, resizes or lets down an item that stands in a placed group. */
    record Pose(SlotRef slot, ItemPose pose) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(9);
            buf.writeUtf(slot.group(), MAX_NAME);
            buf.writeVarInt(slot.index());
            pose.write(buf);
        }
    }

    record Custom(String action, CompoundTag data) implements StationAction {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(6);
            buf.writeUtf(action, MAX_NAME);
            buf.writeNbt(data);
        }
    }
}
