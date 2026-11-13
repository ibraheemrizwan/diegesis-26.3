package com.pockyl.diegesis.api.state;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Items that live somewhere else than in the station: the inventory of another mod's block entity. A slot group bound to
 * a storage ({@link StationState#bindSlots}) reads and writes it directly, so the block keeps working as before (it cooks,
 * hoppers reach it, it drops its items), and what changes there behind the station's back reaches the players by itself.
 *
 * <p>Bind in the constructor of the station's logic, on the server:
 *
 * <pre>{@code
 * public PotLogic(StationContext station) {
 *     Container pot = (Container) station.blockEntity();
 *     station.state().bindSlots("ingredients", SlotStorage.of(pot, 0, 6));
 * }
 * }</pre>
 */
public interface SlotStorage {
    /** How many slots there are; must be the size of the group it is bound to. */
    int size();

    /** The item in a slot; not modified by the library. */
    ItemStack get(int index);

    /** Replaces the item in a slot. The stack is the storage's from now on. */
    void set(int index, ItemStack stack);

    /** {@code count} slots of a vanilla container, from {@code start}. */
    static SlotStorage of(Container container, int start, int count) {
        return new SlotStorage() {
            @Override
            public int size() {
                return count;
            }

            @Override
            public ItemStack get(int index) {
                return container.getItem(start + index);
            }

            @Override
            public void set(int index, ItemStack stack) {
                container.setItem(start + index, stack);
                container.setChanged();
            }
        };
    }
}
