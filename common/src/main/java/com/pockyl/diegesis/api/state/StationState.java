package com.pockyl.diegesis.api.state;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import com.pockyl.diegesis.api.state.StationSchema.SlotGroup;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The live values of a station: the items in its slots, its control values and its computed outputs. The server's copy
 * is the truth; clients hold a mirror that the library keeps in sync. A {@link StationHost} block entity owns one and
 * saves it with {@link #save(HolderLookup.Provider)} / {@link #load(CompoundTag, HolderLookup.Provider)}.
 *
 * <p>Changes are recorded, so only what changed is sent to the clients.
 */
public final class StationState {
    private final StationSchema schema;
    private final Map<String, ItemStack[]> slots = new HashMap<>();
    private final Map<String, SlotStorage> storages = new HashMap<>();
    private final Map<String, Float> controls = new HashMap<>();
    private final Map<String, ItemStack> outputs = new HashMap<>();
    private final Map<String, TankContents> tanks = new HashMap<>();
    private final Map<SlotRef, ItemPose> poses = new HashMap<>();
    private final Set<SlotRef> dirtyPoses = new LinkedHashSet<>();
    private final Set<SlotRef> dirtySlots = new LinkedHashSet<>();
    private final Set<String> dirtyTanks = new LinkedHashSet<>();
    private final Set<String> dirtyControls = new LinkedHashSet<>();
    private final Set<String> dirtyOutputs = new LinkedHashSet<>();
    private CompoundTag data = new CompoundTag();
    private boolean dirtyData;
    private Runnable changeListener = () -> {
    };

    public StationState(StationSchema schema) {
        this.schema = schema;
        for (SlotGroup group : schema.slotGroups().values()) {
            ItemStack[] stacks = new ItemStack[group.size()];
            java.util.Arrays.fill(stacks, ItemStack.EMPTY);
            slots.put(group.name(), stacks);
        }
        schema.controls().forEach((name, spec) -> controls.put(name, spec.initial()));
        schema.outputs().forEach(name -> outputs.put(name, ItemStack.EMPTY));
        schema.tanks().forEach((name, spec) -> tanks.put(name, spec.initial()));
    }

    public StationSchema schema() {
        return schema;
    }

    /** The item in a slot; never null. Do not modify the returned stack, use {@link #setItem}. */
    public ItemStack getItem(SlotRef ref) {
        ItemStack[] group = slots.get(ref.group());
        if (group == null || ref.index() >= group.length) {
            return ItemStack.EMPTY;
        }
        SlotStorage storage = storages.get(ref.group());
        return storage != null ? storage.get(ref.index()) : group[ref.index()];
    }

    public void setItem(SlotRef ref, ItemStack stack) {
        ItemStack[] group = slots.get(ref.group());
        if (group == null || ref.index() >= group.length) {
            throw new IllegalArgumentException("No slot " + ref + " in this station");
        }
        SlotStorage storage = storages.get(ref.group());
        if (storage != null) {
            // The storage's owner may change this stack in place: what the clients were told stays a copy of its own.
            storage.set(ref.index(), stack.isEmpty() ? ItemStack.EMPTY : stack);
            group[ref.index()] = stack.copy();
        } else {
            group[ref.index()] = stack.isEmpty() ? ItemStack.EMPTY : stack;
        }
        dirtySlots.add(ref);
        changeListener.run();
    }

    /**
     * Makes a slot group a window on items kept elsewhere, see {@link SlotStorage}. Only where the state is the truth: in the
     * constructor of the station's logic. A bound group is not part of {@link #save()}: its owner keeps it.
     */
    public void bindSlots(String group, SlotStorage storage) {
        ItemStack[] stacks = slots.get(group);
        if (stacks == null) {
            throw new IllegalArgumentException("No slot group " + group + " in this station");
        }
        if (storage.size() != stacks.length) {
            throw new IllegalArgumentException("Slot group " + group + " has " + stacks.length + " slots, its storage " + storage.size());
        }
        storages.put(group, storage);
        pollStorages();
    }

    /** Whether a slot group shows items kept elsewhere ({@link #bindSlots}). */
    public boolean isBound(String group) {
        return storages.containsKey(group);
    }

    /** Notices what changed in the bound storages since the last look. */
    @ApiStatus.Internal
    public void pollStorages() {
        storages.forEach((name, storage) -> {
            ItemStack[] known = slots.get(name);
            for (int i = 0; i < known.length; i++) {
                ItemStack now = storage.get(i);
                if (!ItemStack.matches(known[i], now)) {
                    known[i] = now.copy();
                    dirtySlots.add(new SlotRef(name, i));
                    changeListener.run();
                }
            }
        });
    }

    /** Where the item of a slot stands, for items placed freely in the scene; null when nothing says so. */
    public ItemPose getPose(SlotRef ref) {
        return poses.get(ref);
    }

    /** Sets where the item of a slot stands; null forgets it. */
    public void setPose(SlotRef ref, ItemPose pose) {
        if (!schema.hasSlot(ref)) {
            throw new IllegalArgumentException("No slot " + ref + " in this station");
        }
        if (java.util.Objects.equals(pose == null ? poses.remove(ref) : poses.put(ref, pose), pose)) {
            return;
        }
        dirtyPoses.add(ref);
        changeListener.run();
    }

    /** All slots of a group, in order. */
    public ItemStack[] getItems(String group) {
        ItemStack[] stacks = slots.get(group);
        if (stacks == null) {
            throw new IllegalArgumentException("No slot group " + group + " in this station");
        }
        SlotStorage storage = storages.get(group);
        if (storage != null) {
            ItemStack[] current = new ItemStack[stacks.length];
            java.util.Arrays.setAll(current, storage::get);
            return current;
        }
        return stacks.clone();
    }

    public float getControl(String name) {
        Float value = controls.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No control " + name + " in this station");
        }
        return value;
    }

    /** Sets a control, clamped and snapped to its steps. */
    public void setControl(String name, float value) {
        ControlSpec spec = schema.controls().get(name);
        if (spec == null) {
            throw new IllegalArgumentException("No control " + name + " in this station");
        }
        float sanitized = spec.sanitize(value);
        Float previous = controls.put(name, sanitized);
        if (previous == null || previous != sanitized) {
            dirtyControls.add(name);
            changeListener.run();
        }
    }

    public ItemStack getOutput(String name) {
        return outputs.getOrDefault(name, ItemStack.EMPTY);
    }

    /** Sets a computed output; the station logic does this, players only take outputs. */
    public void setOutput(String name, ItemStack stack) {
        if (!outputs.containsKey(name)) {
            throw new IllegalArgumentException("No output " + name + " in this station");
        }
        if (!ItemStack.matches(outputs.get(name), stack)) {
            outputs.put(name, stack.isEmpty() ? ItemStack.EMPTY : stack);
            dirtyOutputs.add(name);
            changeListener.run();
        }
    }

    /** What a tank holds; never null. */
    public TankContents getTank(String name) {
        TankContents contents = tanks.get(name);
        if (contents == null) {
            throw new IllegalArgumentException("No tank " + name + " in this station");
        }
        return contents;
    }

    /** Sets a tank's contents (logic: fill, empty, react); more than the capacity is cut off. */
    public void setTank(String name, TankContents contents) {
        TankSpec spec = schema.tanks().get(name);
        if (spec == null) {
            throw new IllegalArgumentException("No tank " + name + " in this station");
        }
        TankContents fitted = contents.amount() > spec.capacity() ? contents.take(spec.capacity()) : contents;
        if (!fitted.equals(tanks.put(name, fitted))) {
            dirtyTanks.add(name);
            changeListener.run();
        }
    }

    /**
     * The station's own data: anything the logic wants to keep and show that is no slot, tank or control (a board position,
     * a score, what has been discovered). It is saved with the state and sent to every client in one piece, so keep it
     * small. This is a copy; change it with {@link #setData}.
     */
    public CompoundTag data() {
        return data.copy();
    }

    /** Replaces the station's own data (see {@link #data()}); only the logic does this. */
    public void setData(CompoundTag value) {
        if (!data.equals(value)) {
            data = value.copy();
            dirtyData = true;
            changeListener.run();
        }
    }

    /** Whether no slot holds an item. */
    public boolean isEmpty() {
        for (ItemStack[] group : slots.values()) {
            for (ItemStack stack : group) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Saving (block entities)
    // ---------------------------------------------------------------------------------------------------------------

    /** Everything as NBT, for a block entity to save ({@code saveAdditional} hands over the registries). */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        CompoundTag slotsTag = new CompoundTag();
        slots.forEach((name, stacks) -> {
            if (storages.containsKey(name)) {
                return;
            }
            ListTag list = new ListTag();
            for (int i = 0; i < stacks.length; i++) {
                if (!stacks[i].isEmpty()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putInt("Slot", i);
                    entry.merge((CompoundTag) ItemStack.CODEC.encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stacks[i]).getOrThrow());
                    list.add(entry);
                }
            }
            slotsTag.put(name, list);
        });
        tag.put("slots", slotsTag);
        CompoundTag controlsTag = new CompoundTag();
        controls.forEach(controlsTag::putFloat);
        tag.put("controls", controlsTag);
        CompoundTag tanksTag = new CompoundTag();
        tanks.forEach((name, contents) -> tanksTag.put(name, contents.save()));
        tag.put("tanks", tanksTag);
        tag.put("data", data.copy());
        if (!poses.isEmpty()) {
            ListTag posesTag = new ListTag();
            poses.forEach((ref, pose) -> {
                CompoundTag entry = pose.save();
                entry.putString("Group", ref.group());
                entry.putInt("Slot", ref.index());
                posesTag.add(entry);
            });
            tag.put("poses", posesTag);
        }
        return tag;
    }

    /** Reads what {@link #save} wrote; unknown names (from an older version of the mod) are ignored. */
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag slotsTag = tag.getCompoundOrEmpty("slots");
        slots.forEach((name, stacks) -> {
            if (storages.containsKey(name)) {
                return;
            }
            java.util.Arrays.fill(stacks, ItemStack.EMPTY);
            ListTag list = slotsTag.getListOrEmpty(name);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompoundOrEmpty(i);
                int index = entry.getIntOr("Slot", 0);
                if (index >= 0 && index < stacks.length) {
                    if (entry.getByte("Count").isPresent() && !entry.contains("count")) {
                        // Saved by a Minecraft 1.20 version of the library: the plain item survives, its old tag does not.
                        entry.putInt("count", entry.getByteOr("Count", (byte)0));
                    }
                    stacks[index] = ItemStack.CODEC.parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), entry).result().orElse(ItemStack.EMPTY);
                }
            }
        });
        CompoundTag controlsTag = tag.getCompoundOrEmpty("controls");
        schema.controls().forEach((name, spec) -> controls.put(name,
                controlsTag.getFloat(name).map(spec::sanitize).orElse(spec.initial())));
        CompoundTag tanksTag = tag.getCompoundOrEmpty("tanks");
        schema.tanks().forEach((name, spec) -> tanks.put(name,
                tanksTag.getCompound(name).isPresent() ? TankContents.load(tanksTag.getCompoundOrEmpty(name)) : spec.initial()));
        data = tag.getCompoundOrEmpty("data").copy();
        poses.clear();
        ListTag posesTag = tag.getListOrEmpty("poses");
        for (int i = 0; i < posesTag.size(); i++) {
            CompoundTag entry = posesTag.getCompoundOrEmpty(i);
            SlotRef ref = new SlotRef(entry.getStringOr("Group", ""), entry.getIntOr("Slot", 0));
            ItemPose pose = ItemPose.load(entry);
            if (pose != null && schema.hasSlot(ref)) {
                poses.put(ref, pose);
            }
        }
        markAllDirty();
        changeListener.run();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Synchronisation (library internals)
    // ---------------------------------------------------------------------------------------------------------------

    @ApiStatus.Internal
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    @ApiStatus.Internal
    public boolean isDirty() {
        return !dirtySlots.isEmpty() || !dirtyPoses.isEmpty() || !dirtyControls.isEmpty() || !dirtyOutputs.isEmpty() || !dirtyTanks.isEmpty() || dirtyData;
    }

    @ApiStatus.Internal
    public void markAllDirty() {
        slots.forEach((name, stacks) -> {
            for (int i = 0; i < stacks.length; i++) {
                dirtySlots.add(new SlotRef(name, i));
            }
        });
        dirtyPoses.addAll(poses.keySet());
        dirtyControls.addAll(controls.keySet());
        dirtyOutputs.addAll(outputs.keySet());
        dirtyTanks.addAll(tanks.keySet());
        dirtyData = true;
    }

    /** Writes the changed values (all of them with {@code full}) and forgets the changes. */
    @ApiStatus.Internal
    public void writeChanges(RegistryFriendlyByteBuf buf, boolean full) {
        if (full) {
            markAllDirty();
        }
        buf.writeVarInt(dirtySlots.size());
        for (SlotRef ref : dirtySlots) {
            buf.writeUtf(ref.group());
            buf.writeVarInt(ref.index());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, getItem(ref));
        }
        buf.writeVarInt(dirtyPoses.size());
        for (SlotRef ref : dirtyPoses) {
            ItemPose pose = poses.get(ref);
            buf.writeUtf(ref.group());
            buf.writeVarInt(ref.index());
            buf.writeBoolean(pose != null);
            if (pose != null) {
                pose.write(buf);
            }
        }
        buf.writeVarInt(dirtyControls.size());
        for (String name : dirtyControls) {
            buf.writeUtf(name);
            buf.writeFloat(controls.get(name));
        }
        buf.writeVarInt(dirtyOutputs.size());
        for (String name : dirtyOutputs) {
            buf.writeUtf(name);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, outputs.get(name));
        }
        buf.writeVarInt(dirtyTanks.size());
        for (String name : dirtyTanks) {
            buf.writeUtf(name);
            tanks.get(name).write(buf);
        }
        buf.writeBoolean(dirtyData);
        if (dirtyData) {
            buf.writeNbt(data);
        }
        clearChanges();
    }

    /** Applies what {@link #writeChanges} wrote; values the schema does not know are skipped. */
    @ApiStatus.Internal
    public void readChanges(RegistryFriendlyByteBuf buf) {
        int slotCount = buf.readVarInt();
        for (int i = 0; i < slotCount; i++) {
            SlotRef ref = new SlotRef(buf.readUtf(), buf.readVarInt());
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack[] group = slots.get(ref.group());
            if (group != null && ref.index() < group.length) {
                group[ref.index()] = stack;
                // A client that binds the group (idle logic) reads the storage: what the server says must land there too, or a
                // guess the server turned down would stay on show.
                SlotStorage storage = storages.get(ref.group());
                if (storage != null) {
                    storage.set(ref.index(), stack.copy());
                }
                dirtySlots.add(ref);
            }
        }
        int poseCount = buf.readVarInt();
        for (int i = 0; i < poseCount; i++) {
            SlotRef ref = new SlotRef(buf.readUtf(), buf.readVarInt());
            ItemPose pose = buf.readBoolean() ? ItemPose.read(buf) : null;
            if (schema.hasSlot(ref)) {
                if (pose == null) {
                    poses.remove(ref);
                } else {
                    poses.put(ref, pose);
                }
                dirtyPoses.add(ref);
            }
        }
        int controlCount = buf.readVarInt();
        for (int i = 0; i < controlCount; i++) {
            String name = buf.readUtf();
            float value = buf.readFloat();
            if (controls.containsKey(name)) {
                controls.put(name, value);
                dirtyControls.add(name);
            }
        }
        int outputCount = buf.readVarInt();
        for (int i = 0; i < outputCount; i++) {
            String name = buf.readUtf();
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            if (outputs.containsKey(name)) {
                outputs.put(name, stack);
                dirtyOutputs.add(name);
            }
        }
        int tankCount = buf.readVarInt();
        for (int i = 0; i < tankCount; i++) {
            String name = buf.readUtf();
            TankContents contents = TankContents.read(buf);
            if (tanks.containsKey(name)) {
                tanks.put(name, contents);
                dirtyTanks.add(name);
            }
        }
        if (buf.readBoolean()) {
            CompoundTag received = buf.readNbt();
            data = received == null ? new CompoundTag() : received;
            dirtyData = true;
        }
        changeListener.run();
    }

    /** What changed since the last call, for the client to react to; then forgets it. */
    @ApiStatus.Internal
    public Changes takeChanges() {
        Changes changes = new Changes(Set.copyOf(dirtySlots), Set.copyOf(dirtyControls), Set.copyOf(dirtyOutputs),
                Set.copyOf(dirtyTanks), dirtyData, Set.copyOf(dirtyPoses));
        clearChanges();
        return changes;
    }

    /** Copies every value of {@code other} (same schema) into this state. */
    @ApiStatus.Internal
    public void copyFrom(StationState other) {
        other.slots.forEach((name, stacks) -> {
            ItemStack[] own = slots.get(name);
            for (int i = 0; i < stacks.length; i++) {
                own[i] = stacks[i].copy();
            }
        });
        controls.putAll(other.controls);
        other.outputs.forEach((name, stack) -> outputs.put(name, stack.copy()));
        tanks.putAll(other.tanks);
        data = other.data.copy();
        poses.clear();
        poses.putAll(other.poses);
        markAllDirty();
        changeListener.run();
    }

    private void clearChanges() {
        dirtySlots.clear();
        dirtyPoses.clear();
        dirtyControls.clear();
        dirtyOutputs.clear();
        dirtyTanks.clear();
        dirtyData = false;
    }

    /** Names of what changed; {@code data} tells whether the station's own data did, {@code poses} which placed items moved. */
    public record Changes(Set<SlotRef> slots, Set<String> controls, Set<String> outputs, Set<String> tanks, boolean data,
            Set<SlotRef> poses) {
        public boolean isEmpty() {
            return slots.isEmpty() && controls.isEmpty() && outputs.isEmpty() && tanks.isEmpty() && !data && poses.isEmpty();
        }
    }
}
