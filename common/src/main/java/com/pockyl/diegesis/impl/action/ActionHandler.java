package com.pockyl.diegesis.impl.action;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.pockyl.diegesis.api.state.ControlSpec;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.StationSchema.SlotGroup;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.api.state.TankSpec;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.impl.action.StationAction.ClickKind;
import com.pockyl.diegesis.impl.action.StationAction.Target;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Checks a player's action against the station's schema and logic and applies it. Item clicks follow vanilla
 * container screens (the cursor carries a stack: the player's inventory menu carried stack), so every check a modified
 * client could try to slip past lives here: unknown slots, items that do not fit, more items than there are.
 */
public final class ActionHandler {
    /** At most this many outputs are taken by one shift-click, like shift-clicking a crafting result. */
    private static final int MAX_TAKE_ALL = 64;
    /**
     * The inventory slot each player's cursor stack was taken from whole: when the station closes it goes back there, so a knife
     * taken from the hotbar is in the hand again, not in the first free slot.
     */
    private static final Map<Player, Integer> TAKEN_FROM = new WeakHashMap<>();

    private ActionHandler() {
    }

    /** Applies {@code action}; returns whether it was accepted (a rejected action changes nothing). */
    public static boolean apply(StationContext station, StationLogic logic, Player player, StationAction action) {
        if (action instanceof StationAction.Click click) {
            return click(station, logic, player, click);
        } else if (action instanceof StationAction.Spread spread) {
            return spread(station, logic, player, spread);
        } else if (action instanceof StationAction.SetControl set) {
            return setControl(station, logic, player, set);
        } else if (action instanceof StationAction.Press press) {
            return press(station, logic, player, press);
        } else if (action instanceof StationAction.Pour pour) {
            return pour(station, logic, player, pour);
        } else if (action instanceof StationAction.Place place) {
            return place(station, logic, player, place);
        } else if (action instanceof StationAction.Pose pose) {
            return pose(station, logic, player, pose);
        } else if (action instanceof StationAction.Custom custom) {
            logic.onCustomAction(station, player, custom.action(), custom.data());
            return true;
        }
        return false;
    }

    /** Whether an action moves items, which only the server may do for real. */
    public static boolean movesItems(StationAction action) {
        return action instanceof StationAction.Click || action instanceof StationAction.Spread || action instanceof StationAction.Place;
    }

    /** The stack on the player's cursor. */
    public static ItemStack carried(Player player) {
        return player.inventoryMenu.getCarried();
    }

    private static void setCarried(Player player, ItemStack stack) {
        player.inventoryMenu.setCarried(stack.isEmpty() ? ItemStack.EMPTY : stack);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Clicks
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean click(StationContext station, StationLogic logic, Player player, StationAction.Click click) {
        if (click.button() != 0 && click.button() != 1) {
            return false;
        }
        if (click.kind() == ClickKind.PUT_AWAY) {
            return putAway(player);
        }
        if (click.target() instanceof Target.Output output) {
            return clickOutput(station, logic, player, output.output(), click.kind());
        }
        Slot slot = slot(station, logic, player, click.target());
        if (slot == null) {
            return false;
        }
        return switch (click.kind()) {
            case PICKUP -> pickup(slot, player, click.button());
            case QUICK_MOVE -> quickMove(station, logic, player, slot);
            case PICKUP_ALL -> pickupAll(station, logic, player);
            case PUT_AWAY -> putAway(player);
        };
    }

    /** Vanilla's left/right click on a slot with whatever is on the cursor. */
    private static boolean pickup(Slot slot, Player player, int button) {
        ItemStack carried = carried(player).copy();
        ItemStack stack = slot.get().copy();
        if (stack.isEmpty()) {
            if (carried.isEmpty() || !slot.mayPlace(carried)) {
                return false;
            }
            int count = Math.min(button == 0 ? carried.getCount() : 1, slot.limit(carried));
            slot.set(carried.split(count));
            setCarried(player, carried);
            return true;
        }
        if (!slot.mayPickup()) {
            return false;
        }
        if (carried.isEmpty()) {
            int count = button == 0 ? stack.getCount() : (stack.getCount() + 1) / 2;
            ItemStack taken = stack.split(count);
            setCarried(player, taken);
            slot.set(stack);
            slot.taken(taken);
            if (slot instanceof InventorySlot inventory && stack.isEmpty()) {
                TAKEN_FROM.put(player, inventory.index());
            } else {
                TAKEN_FROM.remove(player);
            }
            return true;
        }
        if (ItemStack.isSameItemSameComponents(stack, carried)) {
            if (!slot.mayPlace(carried)) {
                return false;
            }
            int room = slot.limit(stack) - stack.getCount();
            int count = Math.min(button == 0 ? carried.getCount() : 1, room);
            if (count <= 0) {
                return false;
            }
            stack.grow(count);
            carried.shrink(count);
            slot.set(stack);
            setCarried(player, carried);
            return true;
        }
        if (slot.mayPlace(carried) && carried.getCount() <= slot.limit(carried)) {
            slot.set(carried);
            setCarried(player, stack);
            slot.taken(stack);
            return true;
        }
        return false;
    }

    /** Shift-click: a station slot empties into the inventory, an inventory slot into the station's slots. */
    private static boolean quickMove(StationContext station, StationLogic logic, Player player, Slot slot) {
        ItemStack stack = slot.get().copy();
        if (stack.isEmpty() || !slot.mayPickup()) {
            return false;
        }
        int before = stack.getCount();
        if (slot.inStation()) {
            player.getInventory().add(stack);
        } else {
            moveIntoStation(station, logic, player, stack);
        }
        if (stack.getCount() == before) {
            return false;
        }
        slot.set(stack);
        slot.taken(stack.copyWithCount(before - stack.getCount()));
        return true;
    }

    /** Fills matching stacks first, then empty slots, in slot order; {@code stack} keeps what did not fit. */
    private static void moveIntoStation(StationContext station, StationLogic logic, Player player, ItemStack stack) {
        List<Slot> slots = stationSlots(station, logic, player);
        slots.removeIf(Slot::isPlaced);
        for (boolean emptyPass : new boolean[] {false, true}) {
            for (Slot target : slots) {
                if (stack.isEmpty()) {
                    return;
                }
                ItemStack there = target.get();
                if (there.isEmpty() != emptyPass || !target.mayPlace(stack)) {
                    continue;
                }
                if (!emptyPass && !ItemStack.isSameItemSameComponents(there, stack)) {
                    continue;
                }
                int moved = Math.min(target.limit(stack) - there.getCount(), stack.getCount());
                if (moved > 0) {
                    target.set(emptyPass ? stack.copyWithCount(moved) : there.copyWithCount(there.getCount() + moved));
                    stack.shrink(moved);
                }
            }
        }
    }

    /** Double click: gathers items like the carried stack from the station and the inventory onto the cursor. */
    private static boolean pickupAll(StationContext station, StationLogic logic, Player player) {
        ItemStack carried = carried(player).copy();
        if (carried.isEmpty()) {
            return false;
        }
        List<Slot> slots = new ArrayList<>(stationSlots(station, logic, player));
        slots.removeIf(Slot::isPlaced);
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            slots.add(new InventorySlot(player.getInventory(), i));
        }
        boolean changed = false;
        // Like vanilla: part stacks first, then full ones.
        for (boolean fullPass : new boolean[] {false, true}) {
            for (Slot slot : slots) {
                if (carried.getCount() >= carried.getMaxStackSize()) {
                    break;
                }
                ItemStack there = slot.get();
                if (there.isEmpty() || !ItemStack.isSameItemSameComponents(there, carried) || !slot.mayPickup()
                        || (there.getCount() >= there.getMaxStackSize()) != fullPass) {
                    continue;
                }
                int taken = Math.min(there.getCount(), carried.getMaxStackSize() - carried.getCount());
                carried.grow(taken);
                slot.set(there.getCount() > taken ? there.copyWithCount(there.getCount() - taken) : ItemStack.EMPTY);
                slot.taken(there.copyWithCount(taken));
                changed = true;
            }
        }
        setCarried(player, carried);
        return changed;
    }

    /** The carried stack spread over several slots: evenly (left) or one per slot (right); the rest stays carried. */
    private static boolean spread(StationContext station, StationLogic logic, Player player, StationAction.Spread spread) {
        ItemStack carried = carried(player).copy();
        if (carried.isEmpty() || spread.targets().isEmpty() || spread.button() != 0 && spread.button() != 1) {
            return false;
        }
        Set<Target> seen = new HashSet<>();
        List<Slot> slots = new ArrayList<>();
        for (Target target : spread.targets()) {
            Slot slot = target instanceof Target.Output ? null : slot(station, logic, player, target);
            if (slot == null || !seen.add(target)) {
                return false;
            }
            ItemStack there = slot.get();
            if (slot.mayPlace(carried) && (there.isEmpty() || ItemStack.isSameItemSameComponents(there, carried))) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            return false;
        }
        int each = spread.button() == 0 ? Math.max(1, carried.getCount() / slots.size()) : 1;
        boolean changed = false;
        for (Slot slot : slots) {
            if (carried.isEmpty()) {
                break;
            }
            ItemStack there = slot.get();
            int moved = Math.min(Math.min(each, carried.getCount()), slot.limit(carried) - there.getCount());
            if (moved <= 0) {
                continue;
            }
            slot.set(there.isEmpty() ? carried.copyWithCount(moved) : there.copyWithCount(there.getCount() + moved));
            carried.shrink(moved);
            changed = true;
        }
        setCarried(player, carried);
        return changed;
    }

    /**
     * The carried stack back into the inventory: to the slot it was taken from while that is empty, else where it fits, as a picked
     * up item goes; what does not fit stays on the cursor.
     */
    private static boolean putAway(Player player) {
        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            return false;
        }
        Integer from = TAKEN_FROM.remove(player);
        if (from != null && player.getInventory().getItem(from).isEmpty()) {
            player.getInventory().setItem(from, carried);
            setCarried(player, ItemStack.EMPTY);
            return true;
        }
        int before = carried.getCount();
        ItemStack rest = carried.copy();
        player.getInventory().add(rest);
        setCarried(player, rest);
        return rest.getCount() < before;
    }

    /** An output: a click takes one result onto the cursor (stacking), a shift-click crafts as many as fit. */
    private static boolean clickOutput(StationContext station, StationLogic logic, Player player, String output, ClickKind kind) {
        if (!station.type().schema().outputs().contains(output)) {
            return false;
        }
        StationState state = station.state();
        if (kind == ClickKind.QUICK_MOVE) {
            boolean took = false;
            for (int i = 0; i < MAX_TAKE_ALL; i++) {
                ItemStack shown = state.getOutput(output);
                if (shown.isEmpty() || !canFitWhole(player, shown)) {
                    break;
                }
                ItemStack result = logic.takeOutput(station, player, output);
                if (result.isEmpty()) {
                    break;
                }
                took = true;
                ItemStack rest = result.copy();
                player.getInventory().add(rest);
                if (!rest.isEmpty()) {
                    player.drop(rest, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
                logic.updateOutputs(station);
                if (!ItemStack.isSameItemSameComponents(state.getOutput(output), shown)) {
                    break;
                }
            }
            return took;
        }
        if (kind != ClickKind.PICKUP) {
            return false;
        }
        ItemStack shown = state.getOutput(output);
        ItemStack carried = carried(player);
        if (shown.isEmpty() || !carried.isEmpty() && (!ItemStack.isSameItemSameComponents(carried, shown)
                || carried.getCount() + shown.getCount() > carried.getMaxStackSize())) {
            return false;
        }
        ItemStack result = logic.takeOutput(station, player, output);
        if (result.isEmpty()) {
            return false;
        }
        setCarried(player, carried.isEmpty() ? result.copy() : carried.copyWithCount(carried.getCount() + result.getCount()));
        logic.updateOutputs(station);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Items placed freely
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean place(StationContext station, StationLogic logic, Player player, StationAction.Place action) {
        SlotGroup group = placedGroup(station, action.slot());
        ItemPose pose = action.pose().sanitized();
        ItemStack carried = carried(player).copy();
        if (group == null || pose == null || carried.isEmpty() || !station.state().getItem(action.slot()).isEmpty()) {
            return false;
        }
        Slot slot = new StationSlot(station, logic, player, group, action.slot());
        if (!slot.mayPlace(carried)) {
            return false;
        }
        // The pose first: the logic hears of the new item from the slot, and may want to know where it stands.
        station.state().setPose(action.slot(), checkedSupport(station, group, action.slot(), pose));
        slot.set(carried.split(slot.limit(carried)));
        setCarried(player, carried);
        return true;
    }

    private static boolean pose(StationContext station, StationLogic logic, Player player, StationAction.Pose action) {
        SlotGroup group = placedGroup(station, action.slot());
        ItemPose pose = action.pose().sanitized();
        if (group == null || pose == null || station.state().getItem(action.slot()).isEmpty()
                || !logic.mayTake(station, player, action.slot())) {
            return false;
        }
        ItemPose checked = checkedSupport(station, group, action.slot(), pose);
        station.state().setPose(action.slot(), checked);
        logic.onPoseChanged(station, player, action.slot(), checked);
        return true;
    }

    private static SlotGroup placedGroup(StationContext station, SlotRef ref) {
        SlotGroup group = station.type().schema().slotGroups().get(ref.group());
        return group != null && group.rules().handPlaced() && ref.index() < group.size() ? group : null;
    }

    /** An item stands on an item that is there, and not on itself. */
    private static ItemPose checkedSupport(StationContext station, SlotGroup group, SlotRef ref, ItemPose pose) {
        int on = pose.on();
        boolean there = on >= 0 && on < group.size() && on != ref.index() && !station.state().getItem(group.ref(on)).isEmpty();
        return there ? pose : pose.standingOn(-1);
    }

    /** The item {@code gone} left its place: what stood on it comes down onto whatever it stood on. */
    private static void bringDown(StationState state, SlotGroup group, SlotRef gone) {
        ItemPose pose = state.getPose(gone);
        state.setPose(gone, null);
        if (pose == null) {
            return;
        }
        for (int i = 0; i < group.size(); i++) {
            SlotRef above = group.ref(i);
            ItemPose standing = state.getPose(above);
            if (standing != null && standing.on() == gone.index()) {
                lower(state, group, above, standing.y() - pose.y());
                state.setPose(above, state.getPose(above).standingOn(pose.on()));
            }
        }
    }

    private static void lower(StationState state, SlotGroup group, SlotRef item, float pixels) {
        state.setPose(item, state.getPose(item).raised(-pixels));
        for (int i = 0; i < group.size(); i++) {
            SlotRef above = group.ref(i);
            ItemPose standing = state.getPose(above);
            if (standing != null && standing.on() == item.index()) {
                lower(state, group, above, pixels);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean setControl(StationContext station, StationLogic logic, Player player, StationAction.SetControl action) {
        ControlSpec spec = station.type().schema().controls().get(action.control());
        if (spec == null || spec.isButton() || spec.isLocked() || !Float.isFinite(action.value())) {
            return false;
        }
        StationState state = station.state();
        float before = state.getControl(action.control());
        state.setControl(action.control(), action.value());
        float after = state.getControl(action.control());
        if (after != before) {
            logic.onControlChanged(station, player, action.control(), after);
        }
        return true;
    }

    private static boolean press(StationContext station, StationLogic logic, Player player, StationAction.Press action) {
        ControlSpec spec = station.type().schema().controls().get(action.control());
        if (spec == null || !spec.isButton() || spec.isLocked()) {
            return false;
        }
        logic.onButton(station, player, action.control());
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Liquids
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean pour(StationContext station, StationLogic logic, Player player, StationAction.Pour action) {
        TankSpec fromSpec = station.type().schema().tanks().get(action.from());
        TankSpec toSpec = station.type().schema().tanks().get(action.to());
        if (fromSpec == null || toSpec == null || action.from().equals(action.to()) || action.amount() < 1) {
            return false;
        }
        StationState state = station.state();
        TankContents from = state.getTank(action.from());
        TankContents to = state.getTank(action.to());
        int amount = Math.min(Math.min(action.amount(), from.amount()), toSpec.capacity() - to.amount());
        if (amount <= 0) {
            return false;
        }
        TankContents part = from.take(amount);
        for (TankContents.Portion portion : part.portions()) {
            if (!toSpec.accepts().test(portion.liquid())) {
                return false;
            }
        }
        if (!logic.mayPour(station, player, action.from(), action.to(), part)) {
            return false;
        }
        state.setTank(action.from(), from.minus(part));
        state.setTank(action.to(), to.plus(part));
        logic.onPoured(station, player, action.from(), action.to(), part);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Leaving
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Hands back what the player still holds: the carried stack and (when {@code lastUser}) the items of slots that
     * return them. To the player's inventory, or dropped at the block when the player is gone.
     */
    public static void returnItems(StationContext station, StationLogic logic, Player player, boolean lastUser) {
        boolean present = player != null && player.isAlive()
                && !(player instanceof ServerPlayer serverPlayer && serverPlayer.hasDisconnected());
        if (player != null) {
            ItemStack carried = carried(player);
            Integer from = TAKEN_FROM.remove(player);
            if (!carried.isEmpty()) {
                setCarried(player, ItemStack.EMPTY);
                if (present && from != null && player.getInventory().getItem(from).isEmpty()) {
                    player.getInventory().setItem(from, carried);
                } else {
                    give(station, player, carried, present);
                }
            }
        }
        if (!lastUser) {
            return;
        }
        StationState state = station.state();
        station.type().schema().slotGroups().values().stream().filter(group -> group.rules().returnOnClose()).forEach(group -> {
            for (int i = 0; i < group.size(); i++) {
                SlotRef ref = group.ref(i);
                ItemStack stack = state.getItem(ref);
                if (!stack.isEmpty()) {
                    state.setItem(ref, ItemStack.EMPTY);
                    give(station, player, stack, present);
                }
            }
        });
        // Tanks that do not keep their contents start over.
        station.type().schema().tanks().forEach((name, spec) -> {
            if (!spec.keep()) {
                state.setTank(name, spec.initial());
            }
        });
        logic.updateOutputs(station);
    }

    private static void give(StationContext station, Player player, ItemStack stack, boolean present) {
        if (present) {
            player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
        } else {
            Containers.dropItemStack(station.level(), station.pos().getX() + 0.5, station.pos().getY() + 1.0,
                    station.pos().getZ() + 0.5, stack);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Slots
    // ---------------------------------------------------------------------------------------------------------------

    private static Slot slot(StationContext station, StationLogic logic, Player player, Target target) {
        if (target instanceof Target.Station stationSlot) {
            SlotRef ref = stationSlot.slot();
            SlotGroup group = station.type().schema().slotGroups().get(ref.group());
            return group != null && ref.index() < group.size() ? new StationSlot(station, logic, player, group, ref) : null;
        }
        if (target instanceof Target.Inventory inventory) {
            int index = inventory.index();
            return index >= 0 && index < Inventory.INVENTORY_SIZE ? new InventorySlot(player.getInventory(), index) : null;
        }
        return null;
    }

    private static List<Slot> stationSlots(StationContext station, StationLogic logic, Player player) {
        List<Slot> slots = new ArrayList<>();
        for (SlotGroup group : station.type().schema().slotGroups().values()) {
            for (int i = 0; i < group.size(); i++) {
                slots.add(new StationSlot(station, logic, player, group, group.ref(i)));
            }
        }
        return slots;
    }

    private static boolean canFitWhole(Player player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        int room = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack there = inventory.getItem(i);
            if (there.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(there, stack)) {
                room += there.getMaxStackSize() - there.getCount();
            }
            if (room >= stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    /** A clickable slot of either side. {@link #set} takes ownership of the stack. */
    private interface Slot {
        ItemStack get();

        void set(ItemStack stack);

        int limit(ItemStack stack);

        boolean mayPlace(ItemStack stack);

        boolean mayPickup();

        boolean inStation();

        default boolean isPlaced() {
            return false;
        }

        /** A player took {@code stack} out of this slot. */
        default void taken(ItemStack stack) {
        }
    }

    private record StationSlot(StationContext station, StationLogic logic, Player player, SlotGroup group, SlotRef ref) implements Slot {
        @Override
        public ItemStack get() {
            return station.state().getItem(ref);
        }

        @Override
        public void set(ItemStack stack) {
            station.state().setItem(ref, stack);
            if (stack.isEmpty() && group.rules().handPlaced()) {
                bringDown(station.state(), group, ref);
            }
            logic.onSlotChanged(station, ref);
            logic.updateOutputs(station);
        }

        @Override
        public int limit(ItemStack stack) {
            return group.rules().limitFor(stack);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return group.rules().accepts().test(stack) && logic.mayPlace(station, player, ref, stack);
        }

        @Override
        public boolean mayPickup() {
            return logic.mayTake(station, player, ref);
        }

        @Override
        public boolean inStation() {
            return true;
        }

        @Override
        public boolean isPlaced() {
            return group.rules().handPlaced();
        }

        @Override
        public void taken(ItemStack stack) {
            logic.onTaken(station, player, ref, stack);
        }
    }

    private record InventorySlot(Inventory inventory, int index) implements Slot {
        @Override
        public ItemStack get() {
            return inventory.getItem(index);
        }

        @Override
        public void set(ItemStack stack) {
            inventory.setItem(index, stack.isEmpty() ? ItemStack.EMPTY : stack);
        }

        @Override
        public int limit(ItemStack stack) {
            return stack.getMaxStackSize();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return true;
        }

        @Override
        public boolean mayPickup() {
            return true;
        }

        @Override
        public boolean inStation() {
            return false;
        }
    }
}
