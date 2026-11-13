package com.pockyl.diegesis.api.state;

import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * What a slot group accepts and what happens to its items.
 *
 * @param maxStack      the largest stack one slot holds (also capped by the item's own stack size)
 * @param accepts       which items may be put in
 * @param returnOnClose items go back to the player when the last player stops working at the block, as with the
 *                      vanilla crafting table; false for stations that keep their items (they need a {@link StationHost})
 * @param handPlaced    the items are put down by hand, anywhere on the surfaces of the scene's {@code "placing"}: shift-clicks
 *                      and double clicks in the inventory pass these slots by
 */
public record SlotRules(int maxStack, Predicate<ItemStack> accepts, boolean returnOnClose, boolean handPlaced) {
    /** Any item, full stacks, returned to the player when they leave. */
    public static final SlotRules DEFAULT = new SlotRules(64, stack -> true, true, false);

    public SlotRules {
        if (maxStack < 1 || maxStack > 64) {
            throw new IllegalArgumentException("maxStack must be 1..64, got " + maxStack);
        }
    }

    public SlotRules maxStack(int value) {
        return new SlotRules(value, accepts, returnOnClose, handPlaced);
    }

    public SlotRules accepts(Predicate<ItemStack> value) {
        return new SlotRules(maxStack, value, returnOnClose, handPlaced);
    }

    /** The items stay in the block between uses; the block entity must implement {@link StationHost}. */
    public SlotRules kept() {
        return new SlotRules(maxStack, accepts, false, handPlaced);
    }

    /** The items are put down by hand on the surfaces of the scene's {@code "placing"}, each with a pose of its own. */
    public SlotRules placed() {
        return new SlotRules(maxStack, accepts, returnOnClose, true);
    }

    /** How many of {@code stack} fit into one slot of this group. */
    public int limitFor(ItemStack stack) {
        return Math.min(maxStack, stack.getMaxStackSize());
    }
}
