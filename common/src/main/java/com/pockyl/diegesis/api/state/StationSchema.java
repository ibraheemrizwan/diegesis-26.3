package com.pockyl.diegesis.api.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The state a station keeps, declared in code on both sides: slot groups, controls and outputs. The scene binds its
 * nodes to these by name; the server checks every player action against them, so a modified client cannot put an item
 * where it does not belong or turn a knob past its end.
 */
public final class StationSchema {
    public static final StationSchema EMPTY = builder().build();

    private final Map<String, SlotGroup> slotGroups;
    private final Map<String, ControlSpec> controls;
    private final Set<String> outputs;
    private final Map<String, TankSpec> tanks;

    private StationSchema(Builder builder) {
        this.slotGroups = Collections.unmodifiableMap(new LinkedHashMap<>(builder.slotGroups));
        this.controls = Collections.unmodifiableMap(new LinkedHashMap<>(builder.controls));
        this.outputs = Collections.unmodifiableSet(new LinkedHashSet<>(builder.outputs));
        this.tanks = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tanks));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, SlotGroup> slotGroups() {
        return slotGroups;
    }

    public Map<String, ControlSpec> controls() {
        return controls;
    }

    public Set<String> outputs() {
        return outputs;
    }

    public Map<String, TankSpec> tanks() {
        return tanks;
    }

    public boolean hasSlot(SlotRef ref) {
        SlotGroup group = slotGroups.get(ref.group());
        return group != null && ref.index() < group.size();
    }

    public boolean hasItems() {
        return !slotGroups.isEmpty() || !outputs.isEmpty();
    }

    /** Whether any slot or tank keeps its contents between uses, which needs a {@link StationHost}. */
    public boolean keepsItems() {
        return slotGroups.values().stream().anyMatch(group -> !group.rules().returnOnClose())
                || tanks.values().stream().anyMatch(TankSpec::keep);
    }

    /** A named row of slots. */
    public record SlotGroup(String name, int size, SlotRules rules) {
        public SlotGroup {
            if (size < 1 || size > 256) {
                throw new IllegalArgumentException("Slot group \"" + name + "\" must have 1..256 slots, got " + size);
            }
        }

        public SlotRef ref(int index) {
            return new SlotRef(name, index);
        }
    }

    public static final class Builder {
        private final Map<String, SlotGroup> slotGroups = new LinkedHashMap<>();
        private final Map<String, ControlSpec> controls = new LinkedHashMap<>();
        private final Set<String> outputs = new LinkedHashSet<>();
        private final Map<String, TankSpec> tanks = new LinkedHashMap<>();

        private Builder() {
        }

        /** A tank for liquids (a flask, a vial); the scene shows it with a node's {@code tank}. */
        public Builder tank(String name, TankSpec spec) {
            checkName(name);
            tanks.put(name, spec);
            return this;
        }

        /** {@code count} slots named {@code name[0]} .. {@code name[count - 1]} that take any item. */
        public Builder slots(String name, int count) {
            return slots(name, count, SlotRules.DEFAULT);
        }

        public Builder slots(String name, int count, SlotRules rules) {
            checkName(name);
            slotGroups.put(name, new SlotGroup(name, count, rules));
            return this;
        }

        public Builder control(String name, ControlSpec spec) {
            checkName(name);
            controls.put(name, spec);
            return this;
        }

        /** A result the station logic computes from its state, such as a crafting result. */
        public Builder output(String name) {
            checkName(name);
            outputs.add(name);
            return this;
        }

        public StationSchema build() {
            return new StationSchema(this);
        }

        private void checkName(String name) {
            if (!name.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("Names are lower case letters, digits and _: " + name);
            }
            if (slotGroups.containsKey(name) || controls.containsKey(name) || outputs.contains(name) || tanks.containsKey(name)) {
                throw new IllegalArgumentException("Name used twice: " + name);
            }
        }
    }
}
