package com.pockyl.diegesis.gametest;

import com.google.gson.JsonParser;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.IOException;
import com.pockyl.diegesis.impl.scene.NodeTemplates;
import com.pockyl.diegesis.impl.liquid.LiquidVolume;
import com.pockyl.diegesis.api.scene.CameraGlance;
import com.pockyl.diegesis.api.scene.CameraRig;
import com.pockyl.diegesis.api.scene.Grabbable;
import com.pockyl.diegesis.api.scene.InventoryDrawer;
import com.pockyl.diegesis.api.scene.Visual;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.ControlBinding;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.scene.InventoryLayout;
import com.pockyl.diegesis.api.scene.SceneDefinition;
import com.pockyl.diegesis.api.state.ControlSpec;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.api.state.TankContents;
import com.pockyl.diegesis.api.state.TankSpec;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.action.ActionHandler;
import com.pockyl.diegesis.impl.action.StationAction;
import com.pockyl.diegesis.impl.action.StationAction.ClickKind;
import com.pockyl.diegesis.impl.action.StationAction.Target;
import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.impl.network.Presence;
import com.pockyl.diegesis.impl.server.StationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}: the server half of the library - action checks
 * (no duplication, no items where they do not belong), state saving and syncing, scene files, sessions and their load.
 */
public final class ModGameTests {
    private static final String GRID = "grid";
    private static final String FUEL = "fuel";
    private static final String RESULT = "result";
    private static final String DIAL = "dial";
    private static final String LEVER = "lever";
    private static final String BELL = "bell";
    private static final String STATUS = "status";
    private static final String SHELF = "shelf";

    private static final Identifier WATER = Identifier.withDefaultNamespace("water");
    private static final Identifier OIL = Identifier.fromNamespaceAndPath("diegesis", "test_oil");
    private static final Identifier ACID = Identifier.fromNamespaceAndPath("diegesis", "test_acid");

    /** Grid of 4 any-item slots, a fuel slot for coal only (max 16), an output, a stepped dial, a lever and a button. */
    static final StationSchema SCHEMA = StationSchema.builder()
            .slots(GRID, 4)
            .slots(FUEL, 1, SlotRules.DEFAULT.maxStack(16).accepts(stack -> stack.is(Items.COAL)))
            .slots(SHELF, 4, SlotRules.DEFAULT.maxStack(1).placed())
            .output(RESULT)
            .control(DIAL, ControlSpec.stepped(0.0F, 10.0F, 5, 2.0F))
            .control(LEVER, ControlSpec.toggle(false))
            .control(BELL, ControlSpec.button())
            .control(STATUS, ControlSpec.toggle(false).locked())
            .tank("vial", TankSpec.of(100).filledWith(WATER, 80).pourRate(200))
            .tank("beaker", TankSpec.of(50).accepts(liquid -> liquid.equals(WATER) || liquid.equals(OIL)))
            .tank("pot", TankSpec.of(500))
            .tank("drum", TankSpec.of(1000).filledWith(WATER, 1000).pourRate(100))
            .build();

    private static StationType testStation;

    public ModGameTests() {
    }

    private static StationType seatStation;

    /** A station on smooth basalt with a seat to stand at in front and one to sit at behind. */
    static synchronized StationType seatStation() {
        if (seatStation == null) {
            seatStation = Diegesis.station(DiegesisMod.id("seat_station"))
                    .blocks(Blocks.SMOOTH_BASALT)
                    .orientation(StationType.Orientation.FIXED)
                    .seat(com.pockyl.diegesis.api.station.Seat.at("front", 8.0F, 0.0F, -8.0F))
                    .seat(com.pockyl.diegesis.api.station.Seat.sitting("back", 8.0F, 8.0F, 24.0F))
                    .register();
        }
        return seatStation;
    }

    /** A test station on lodestones: its output is a copy of grid[0] once two of it lie there. */
    static synchronized StationType testStation() {
        if (testStation == null) {
            testStation = Diegesis.station(DiegesisMod.id("test_station"))
                    .blocks(Blocks.LODESTONE)
                    .schema(SCHEMA)
                    .logic(station -> new TestLogic())
                    .register();
        }
        return testStation;
    }

    @GameTest(structure = "diegesis:empty")
    public void modLoads(GameTestHelper helper) {
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(structure = "diegesis:empty")
    public void clicksMoveStacksLikeVanilla(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.STICK, 5));
        // Left click on the inventory slot picks the whole stack up; left click on an empty station slot puts it down.
        check(helper, click(station, player, inv(0), 0), "pick up from the inventory");
        check(helper, carried(player).getCount() == 5 && player.getInventory().getItem(0).isEmpty(), "five on the cursor");
        check(helper, click(station, player, grid(0), 0), "put down on the station");
        check(helper, count(station, 0) == 5 && carried(player).isEmpty(), "five sticks in the grid");
        // Right click takes half (rounded up), right click on an empty slot puts one down.
        check(helper, click(station, player, grid(0), 1), "take half");
        check(helper, carried(player).getCount() == 3 && count(station, 0) == 2, "3 carried, 2 left");
        check(helper, click(station, player, grid(1), 1), "put one");
        check(helper, carried(player).getCount() == 2 && count(station, 1) == 1, "one placed");
        // The same item merges; a different one swaps.
        player.getInventory().setItem(5, new ItemStack(Items.FLINT, 4));
        check(helper, click(station, player, grid(1), 0), "merge back onto the stick");
        check(helper, count(station, 1) == 3 && carried(player).isEmpty(), "merged");
        check(helper, click(station, player, inv(5), 0), "pick flint");
        check(helper, click(station, player, grid(1), 0), "swap flint with sticks");
        check(helper, station.state.getItem(SlotRef.of(GRID, 1)).is(Items.FLINT) && carried(player).is(Items.STICK)
                && carried(player).getCount() == 3, "swapped");
        // Nothing is ever made or lost.
        check(helper, total(station, player, Items.STICK) == 5 && total(station, player, Items.FLINT) == 4, "counts kept");
        helper.succeed();
    }

    /** A right click nothing takes puts the cursor's stack away: back to its slot while that is free, else where it fits. */
    @GameTest(structure = "diegesis:empty")
    public void aRightClickPutsTheHandAway(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(7, new ItemStack(Items.SHEARS));
        check(helper, click(station, player, inv(7), 0), "take the shears");
        check(helper, putAway(station, player), "put away");
        check(helper, carried(player).isEmpty() && player.getInventory().getItem(7).is(Items.SHEARS), "back in their slot");
        check(helper, click(station, player, inv(7), 0), "take them again");
        player.getInventory().setItem(7, new ItemStack(Items.FLINT));
        check(helper, putAway(station, player), "put away with the slot taken");
        check(helper, carried(player).isEmpty() && total(station, player, Items.SHEARS) == 1 && !player.getInventory().getItem(7).is(Items.SHEARS),
                "somewhere else");
        check(helper, !putAway(station, player), "nothing to put away");
        helper.succeed();
    }

    private static boolean putAway(TestContext station, Player player) {
        return ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Click(inv(0), 1, ClickKind.PUT_AWAY));
    }

    /** A bound group is a window: the storage is the truth both ways, and changes made behind the state's back are noticed. */
    @GameTest(structure = "diegesis:empty")
    public void boundSlotsAreAWindowOnTheirStorage(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        net.minecraft.world.SimpleContainer chest = new net.minecraft.world.SimpleContainer(6);
        chest.setItem(2, new ItemStack(Items.FLINT, 3));
        station.state.bindSlots(GRID, com.pockyl.diegesis.api.state.SlotStorage.of(chest, 2, 4));
        check(helper, station.state.getItem(SlotRef.of(GRID, 0)).is(Items.FLINT) && station.state.isBound(GRID), "reads through");
        station.state.takeChanges();
        player.getInventory().setItem(0, new ItemStack(Items.STICK, 5));
        click(station, player, inv(0), 0);
        click(station, player, grid(1), 0);
        check(helper, chest.getItem(3).is(Items.STICK) && chest.getItem(3).getCount() == 5, "writes through");
        station.state.takeChanges();
        // The owner of the items changes them in place, as a machine does.
        chest.getItem(3).shrink(2);
        station.state.pollStorages();
        check(helper, station.state.takeChanges().slots().contains(SlotRef.of(GRID, 1)), "a change in the storage is noticed");
        station.state.pollStorages();
        check(helper, station.state.takeChanges().isEmpty(), "and only once");
        check(helper, !station.state.save(registries(helper)).getCompoundOrEmpty("slots").contains(GRID), "the owner saves bound slots");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void clicksRespectSlotRules(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.STICK, 8));
        player.getInventory().setItem(1, new ItemStack(Items.COAL, 64));
        click(station, player, inv(0), 0);
        check(helper, !click(station, player, fuel(), 0), "sticks are not fuel");
        click(station, player, inv(0), 0);
        click(station, player, inv(1), 0);
        check(helper, click(station, player, fuel(), 0), "coal is fuel");
        check(helper, station.state.getItem(SlotRef.of(FUEL)).getCount() == 16 && carried(player).getCount() == 48,
                "fuel slot holds at most 16, the rest stays on the cursor");
        check(helper, !click(station, player, new Target.Station(SlotRef.of(GRID, 9)), 0), "no slot grid[9]");
        check(helper, !click(station, player, new Target.Station(SlotRef.of("nope")), 0), "no slot group nope");
        check(helper, !click(station, player, inv(40), 0), "no inventory slot 40 here");
        check(helper, !ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Click(grid(0), 7, ClickKind.PICKUP)),
                "no mouse button 7");
        check(helper, total(station, player, Items.COAL) == 64, "coal kept");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void shiftDoubleClickAndSpread(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        // Shift-click from the inventory fills the station's slots in order.
        check(helper, quick(station, player, inv(0)), "shift into the station");
        check(helper, count(station, 0) == 10 && player.getInventory().getItem(0).isEmpty(), "all ten in grid[0]");
        // Pick them up and spread over three slots: 3 each, one left on the cursor.
        click(station, player, grid(0), 0);
        check(helper, ActionHandler.apply(station, StationLogic.NONE, player,
                new StationAction.Spread(List.of(grid(1), grid(2), grid(3)), 0)), "spread");
        check(helper, count(station, 1) == 3 && count(station, 2) == 3 && count(station, 3) == 3 && carried(player).getCount() == 1,
                "3 + 3 + 3, one carried");
        // Double click gathers them back onto the cursor.
        check(helper, ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Click(grid(1), 0, ClickKind.PICKUP_ALL)),
                "gather");
        check(helper, carried(player).getCount() == 10 && station.state.isEmpty(), "all ten gathered");
        // Shift-click from the station sends to the inventory.
        click(station, player, grid(2), 0);
        check(helper, quick(station, player, grid(2)), "shift back to the inventory");
        check(helper, station.state.getItem(SlotRef.of(GRID, 2)).isEmpty() && player.getInventory().countItem(Items.IRON_INGOT) == 10,
                "back in the inventory");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void outputsComeFromTheLogic(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        TestLogic logic = new TestLogic();
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        ActionHandler.apply(station, logic, player, new StationAction.Click(inv(0), 0, ClickKind.PICKUP));
        ActionHandler.apply(station, logic, player, new StationAction.Click(grid(0), 0, ClickKind.PICKUP));
        check(helper, station.state.getOutput(RESULT).is(Items.DIAMOND), "output offered");
        Target output = new Target.Output(RESULT);
        check(helper, ActionHandler.apply(station, logic, player, new StationAction.Click(output, 0, ClickKind.PICKUP)), "output taken");
        check(helper, count(station, 0) == 3 && carried(player).getCount() == 1, "two used, one on the cursor");
        check(helper, ActionHandler.apply(station, logic, player, new StationAction.Click(output, 0, ClickKind.PICKUP)), "stacks on it");
        check(helper, carried(player).getCount() == 2 && count(station, 0) == 1, "two carried");
        check(helper, station.state.getOutput(RESULT).isEmpty(), "output gone once the input is short");
        check(helper, !ActionHandler.apply(station, logic, player, new StationAction.Click(output, 0, ClickKind.QUICK_MOVE)),
                "nothing to take");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void controlsStayInRange(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.SetControl(DIAL, 7.3F));
        check(helper, station.state.getControl(DIAL) == 8.0F, "snapped to a step of 2: " + station.state.getControl(DIAL));
        ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.SetControl(DIAL, 1000.0F));
        check(helper, station.state.getControl(DIAL) == 10.0F, "clamped to the maximum");
        check(helper, !ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.SetControl(DIAL, Float.NaN)),
                "NaN rejected");
        check(helper, !ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.SetControl(BELL, 1.0F)),
                "a button has no value");
        check(helper, !ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Press(DIAL)), "a dial is not a button");
        check(helper, ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Press(BELL)), "a button is pressed");
        check(helper, !ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.SetControl(STATUS, 1.0F))
                && station.state.getControl(STATUS) == 0.0F, "only the logic sets a locked control");
        helper.succeed();
    }

    private static StationType tallStation;

    /** Two stacked targets are one station that lives at the lower block. */
    static synchronized StationType tallStation() {
        if (tallStation == null) {
            tallStation = Diegesis.station(DiegesisMod.id("test_tall"))
                    .block(() -> Blocks.TARGET)
                    .anchor((level, pos, state) -> level.getBlockState(pos.below()).is(Blocks.TARGET) ? pos.below() : pos)
                    .register();
        }
        return tallStation;
    }

    @GameTest(structure = "diegesis:box")
    public void multiblocksOpenAtTheirMainBlock(GameTestHelper helper) {
        tallStation();
        BlockPos lower = new BlockPos(3, 1, 3);
        helper.setBlock(lower, Blocks.TARGET);
        helper.setBlock(lower.above(), Blocks.TARGET);
        check(helper, Diegesis.stationFor(helper.getLevel().getBlockState(helper.absolutePos(lower.above()))).isPresent(),
                "a block given through a supplier is found");
        ServerPlayer player = mockPlayer(helper);
        Vec3 standing = Vec3.atBottomCenterOf(helper.absolutePos(lower.north()));
        player.teleportTo(standing.x, standing.y, standing.z);
        StationManager manager = StationManager.get(helper.getLevel().getServer());
        check(helper, !manager.openWithoutChannelCheck(player, helper.absolutePos(lower.above())), "not at the upper part");
        check(helper, manager.openWithoutChannelCheck(player, helper.absolutePos(lower)), "at the main block");
        manager.close(player, false);
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void pouringKeepsTheVolume(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        check(helper, pour(station, player, "vial", "beaker", 100), "pour");
        check(helper, station.state.getTank("beaker").amount() == 50 && station.state.getTank("vial").amount() == 30,
                "only what fits: 50 in the beaker, 30 left");
        check(helper, !pour(station, player, "vial", "beaker", 10), "a full beaker takes nothing");
        check(helper, !pour(station, player, "vial", "vial", 10), "not into itself");
        check(helper, !pour(station, player, "vial", "nowhere", 10), "no such tank");
        station.state.setTank("vial", TankContents.of(ACID, 40));
        station.state.setTank("beaker", TankContents.EMPTY);
        check(helper, !pour(station, player, "vial", "beaker", 10), "the beaker refuses acid");
        // A mixture leaves in proportion.
        station.state.setTank("vial", TankContents.of(WATER, 60).plus(TankContents.of(ACID, 20)));
        check(helper, pour(station, player, "vial", "pot", 40), "pour a mixture");
        TankContents pot = station.state.getTank("pot");
        check(helper, pot.amountOf(WATER) == 30 && pot.amountOf(ACID) == 10, "3:1 kept: " + pot);
        check(helper, station.state.getTank("vial").amount() + pot.amount() == 80, "nothing made or lost");
        // Saved and synced like the rest of the state.
        StationState loaded = new StationState(SCHEMA);
        loaded.load(station.state.save(registries(helper)), registries(helper));
        check(helper, loaded.getTank("pot").equals(pot), "tank saved and loaded");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:box")
    public void theServerPacesPouring(GameTestHelper helper) {
        testStation();
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.LODESTONE);
        ServerPlayer player = mockPlayer(helper);
        Vec3 standing = Vec3.atBottomCenterOf(helper.absolutePos(pos.north()));
        player.teleportTo(standing.x, standing.y, standing.z);
        StationManager manager = StationManager.get(helper.getLevel().getServer());
        check(helper, manager.openWithoutChannelCheck(player, helper.absolutePos(pos)), "opened");
        StationContext station = Diegesis.activeStation(helper.getLevel(), helper.absolutePos(pos)).orElseThrow();
        // A cheating client asks for a lot, many times in one tick: the drum pours at 100 mB/s.
        for (int i = 0; i < 10; i++) {
            manager.handle(player, new Packets.Action(helper.absolutePos(pos), new StationAction.Pour("drum", "pot", 300)));
        }
        int poured = station.state().getTank("pot").amount();
        check(helper, poured > 0 && poured <= 100, "at most a second's worth at once: " + poured);
        manager.close(player, false);
        helper.succeed();
    }

    private static boolean pour(TestContext station, Player player, String from, String to, int amount) {
        return ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Pour(from, to, amount));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // State and messages
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(structure = "diegesis:empty")
    public void placedItemsStandWhereTheyArePut(GameTestHelper helper) {
        TestContext station = new TestContext(helper.getLevel());
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Items.BOOK, 3));
        check(helper, click(station, player, inv(0), 0), "take the books");
        org.joml.Quaternionf turned = new org.joml.Quaternionf().rotateY(1.0F);
        ItemPose low = new ItemPose(5.0F, 12.0F, 8.0F, 2.0F, turned, 1.0F, -1);
        ItemPose middle = new ItemPose(5.0F, low.top(), 8.0F, 1.0F, turned, 0.5F, 0);
        ItemPose high = new ItemPose(5.0F, middle.top(), 8.0F, 1.0F, turned, 0.5F, 1);
        SlotRef first = SlotRef.of(SHELF, 0);
        SlotRef second = SlotRef.of(SHELF, 1);
        SlotRef third = SlotRef.of(SHELF, 2);
        check(helper, place(station, player, first, low), "put one down");
        check(helper, carried(player).getCount() == 2 && station.state.getItem(first).getCount() == 1, "one at a time, the rest stays in the hand");
        check(helper, low.equals(station.state.getPose(first)), "it stands as it was put");
        check(helper, !place(station, player, first, middle), "a taken place is taken");
        check(helper, !place(station, player, SlotRef.of(GRID, 0), middle), "only placed groups");
        check(helper, !place(station, player, second, new ItemPose(Float.NaN, 0.0F, 0.0F, 0.0F, turned, 1.0F, -1)), "no nonsense numbers");
        check(helper, place(station, player, second, middle) && place(station, player, third, high), "a tower");
        ItemPose tilted = new ItemPose(5.0F, 12.0F, 8.0F, 3.0F, new org.joml.Quaternionf().rotateX(0.5F), 1.5F, -1);
        check(helper, ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Pose(first, tilted)), "turn it");
        check(helper, !ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Pose(SlotRef.of(SHELF, 3), tilted)), "nothing to turn");
        // Taken away, what stood on it comes down by as much as it was high.
        ItemPose before = station.state.getPose(third);
        float fall = middle.y() - tilted.y();
        check(helper, click(station, player, new Target.Station(first), 0) && carried(player).is(Items.BOOK), "take the lowest");
        check(helper, station.state.getPose(first) == null, "its pose is gone");
        ItemPose landed = station.state.getPose(second);
        check(helper, landed.y() == tilted.y() && landed.on() == -1, "the next one stands on the surface");
        check(helper, station.state.getPose(third).y() == before.y() - fall && station.state.getPose(third).on() == 1, "and the top one on it");
        // Shift-clicks and double clicks pass placed slots by.
        player.getInventory().setItem(1, new ItemStack(Items.BOOK, 2));
        check(helper, quick(station, player, inv(1)), "shift-click goes to the grid");
        check(helper, station.state.getItem(SlotRef.of(SHELF, 3)).isEmpty(), "not to the shelf");

        StationState loaded = new StationState(SCHEMA);
        loaded.load(station.state.save(registries(helper)), registries(helper));
        check(helper, station.state.getPose(third).equals(loaded.getPose(third)), "poses are saved");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        station.state.writeChanges(buf, true);
        StationState mirror = new StationState(SCHEMA);
        mirror.readChanges(buf);
        check(helper, station.state.getPose(second).equals(mirror.getPose(second)), "poses reach the clients");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void strokesAreForNodesWithoutARole(GameTestHelper helper) {
        check(helper, NodeDefinition.builder("zone").strokes().build().strokes(), "a role-less node takes strokes");
        boolean refused = false;
        try {
            NodeDefinition.builder("zone").strokes().slot(com.pockyl.diegesis.api.scene.SlotBinding.of(SlotRef.of(GRID, 0))).build();
        } catch (RuntimeException e) {
            refused = true;
        }
        check(helper, refused, "a slot does not");
        helper.succeed();
    }

    private static boolean place(TestContext station, Player player, SlotRef slot, ItemPose pose) {
        return ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Place(slot, pose));
    }

    @GameTest(structure = "diegesis:empty")
    public void stateSavesAndSyncs(GameTestHelper helper) {
        StationState state = new StationState(SCHEMA);
        state.setItem(SlotRef.of(GRID, 3), new ItemStack(Items.BOOK, 2));
        state.setControl(DIAL, 6.0F);
        state.setControl(LEVER, 1.0F);
        StationState loaded = new StationState(SCHEMA);
        loaded.load(state.save(registries(helper)), registries(helper));
        check(helper, loaded.getItem(SlotRef.of(GRID, 3)).getCount() == 2 && loaded.getControl(DIAL) == 6.0F
                && loaded.getControl(LEVER) == 1.0F, "save and load");

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        state.writeChanges(buf, true);
        StationState mirror = new StationState(SCHEMA);
        mirror.readChanges(buf);
        check(helper, ItemStack.matches(mirror.getItem(SlotRef.of(GRID, 3)), state.getItem(SlotRef.of(GRID, 3))), "full sync");
        state.setControl(DIAL, 4.0F);
        RegistryFriendlyByteBuf delta = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        state.writeChanges(delta, false);
        mirror.takeChanges();
        mirror.readChanges(delta);
        StationState.Changes changes = mirror.takeChanges();
        check(helper, mirror.getControl(DIAL) == 4.0F && changes.controls().equals(java.util.Set.of(DIAL)) && changes.slots().isEmpty(),
                "a delta carries only the change");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void stationDataSavesAndSyncs(GameTestHelper helper) {
        StationState state = new StationState(SCHEMA);
        CompoundTag data = new CompoundTag();
        data.putString("fen", "8/8/8/8/8/8/8/8");
        state.setData(data);
        data.putString("fen", "changed afterwards");
        check(helper, state.data().getStringOr("fen", "").startsWith("8/"), "setData keeps its own copy");
        StationState loaded = new StationState(SCHEMA);
        loaded.load(state.save(registries(helper)), registries(helper));
        check(helper, loaded.data().equals(state.data()), "saved and loaded");

        RegistryFriendlyByteBuf full = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        state.writeChanges(full, true);
        StationState mirror = new StationState(SCHEMA);
        mirror.readChanges(full);
        check(helper, mirror.data().equals(state.data()) && mirror.takeChanges().data(), "a full sync carries the data");
        state.setControl(DIAL, 4.0F);
        RegistryFriendlyByteBuf control = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        state.writeChanges(control, false);
        mirror.readChanges(control);
        check(helper, !mirror.takeChanges().data(), "a control's delta does not");
        CompoundTag same = state.data();
        state.setData(same);
        check(helper, !state.isDirty(), "the same data again changes nothing");
        same.putInt("move", 1);
        state.setData(same);
        RegistryFriendlyByteBuf delta = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        state.writeChanges(delta, false);
        mirror.readChanges(delta);
        check(helper, mirror.data().getIntOr("move", 0) == 1 && mirror.takeChanges().data(), "new data arrives");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:box")
    public void playersTakeTheNearestFreeSeatAndSit(GameTestHelper helper) {
        seatStation();
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.SMOOTH_BASALT);
        BlockPos absolute = helper.absolutePos(pos);
        ServerPlayer first = mockPlayer(helper);
        ServerPlayer second = mockPlayer(helper);
        ServerPlayer third = mockPlayer(helper);
        try {
            StationManager manager = StationManager.get(helper.getLevel().getServer());
            Vec3 south = Vec3.atBottomCenterOf(absolute.south(2));
            Vec3 north = Vec3.atBottomCenterOf(absolute.north(2));
            first.teleportTo(south.x, south.y, south.z);
            second.teleportTo(south.x, south.y, south.z);
            third.teleportTo(north.x, north.y, north.z);
            check(helper, manager.openWithoutChannelCheck(first, absolute), "first opened");
            StationContext station = Diegesis.activeStation(helper.getLevel(), absolute).orElseThrow();
            check(helper, station.facing() == Direction.NORTH, "the block's own facing: " + station.facing());
            check(helper, station.seatOf(first).filter("back"::equals).isPresent(), "the seat behind the block is nearest: " + station.seatOf(first));
            check(helper, first.getVehicle() != null, "a sitting seat sits the player down");
            check(helper, manager.openWithoutChannelCheck(second, absolute), "second opened");
            check(helper, station.seatOf(second).filter("front"::equals).isPresent(), "the other seat is left");
            check(helper, second.getVehicle() == null, "a place to stand at");
            check(helper, station.playerAt("back").filter(first::equals).isPresent(), "who sits there");
            check(helper, manager.openWithoutChannelCheck(third, absolute), "with the seats taken one can still watch");
            check(helper, station.seatOf(third).isEmpty(), "and has no seat");
            var mount = first.getVehicle();
            manager.close(first, false);
            check(helper, first.getVehicle() == null && mount.isRemoved(), "leaving stands up and the mount is gone");
            check(helper, station.playerAt("back").isEmpty(), "the seat is free again");
            manager.close(second, false);
            manager.close(third, false);
        } finally {
            remove(first);
            remove(second);
            remove(third);
        }
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void messagesRoundTrip(GameTestHelper helper) {
        List<Packets.ClientPacket> client = List.of(
                new Packets.Open(new BlockPos(1, 2, 3)),
                new Packets.Close(),
                new Packets.Action(BlockPos.ZERO, new StationAction.Click(grid(2), 1, ClickKind.QUICK_MOVE)),
                new Packets.Action(BlockPos.ZERO, new StationAction.Spread(List.of(grid(0), inv(7), fuel()), 0)),
                new Packets.Action(BlockPos.ZERO, new StationAction.Click(new Target.Output(RESULT), 0, ClickKind.PICKUP)),
                new Packets.Action(BlockPos.ZERO, new StationAction.Custom("pour", new CompoundTag())),
                new Packets.PresenceUpdate(BlockPos.ZERO, Presence.holding("vial", -1, ItemStack.EMPTY, new Vector3f(1, 2, 3),
                        new Vector3f(4, 5, 6))));
        for (Packets.ClientPacket packet : client) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
            packet.write(buf);
            Packets.ClientPacket read = Packets.readClient(buf);
            check(helper, read.getClass() == packet.getClass() && buf.readableBytes() == 0, "round trip " + packet);
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries(helper));
        new Packets.Effect(BlockPos.ZERO, DiegesisMod.id("spark"), "flask", new CompoundTag()).write(buf);
        check(helper, Packets.readServer(buf) instanceof Packets.Effect effect && effect.node().equals("flask"), "effect round trip");
        check(helper, !Presence.holding("", -1, ItemStack.EMPTY, new Vector3f(Float.NaN), new Vector3f()).isPlausible(), "NaN presence dropped");
        check(helper, !Presence.holding("", -1, ItemStack.EMPTY, new Vector3f(1.0e6F), new Vector3f()).isPlausible(), "far presence dropped");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void scenesParseAndReportErrors(GameTestHelper helper) {
        String good = """
                {"camera": {"position": [8, 30, -4], "fov": 50},
                 "nodes": [
                   {"id": "flask", "pos": [8, 16, 8], "visual": {"model": "mymod:diegesis/flask", "tint": "#80FFFFFF"},
                    "grabbable": {"lift": 3}},
                   {"id": "knob", "visual": {"block": "minecraft:stone_button[face=floor]"},
                    "control": {"control": "gas", "type": "knob"},
                    "children": [{"id": "cap", "pos": [0, 2, 0], "scale": [1, 0.5, 1]}]},
                   {"id": "cell", "slot": {"slot": "grid[2]", "display": "upright"}}
                 ]}""";
        DataResult<SceneDefinition> parsed = parse(good);
        check(helper, parsed.error().isEmpty(), "good scene parses: " + parsed.error().map(Object::toString).orElse(""));
        SceneDefinition scene = parsed.result().orElseThrow();
        List<String> ids = new ArrayList<>();
        scene.forEachNode(node -> ids.add(node.id()));
        check(helper, ids.equals(List.of("flask", "knob", "cap", "cell")), "node tree " + ids);
        check(helper, scene.nodes().get(0).visual().orElseThrow().translucent(), "alpha tint makes it translucent");

        check(helper, parse("{\"nodes\": [{\"id\": \"a\"}, {\"id\": \"a\"}]}").error().isPresent(), "duplicate ids");
        check(helper, parse("{\"nodes\": [{\"id\": \"a\", \"grabbable\": {}, \"slot\": {\"slot\": \"x\"}}]}").error().isPresent(),
                "two roles on one node");
        check(helper, parse("{\"nodes\": [{\"id\": \"a\", \"visual\": {\"model\": \"x:y\", \"item\": \"minecraft:stick\"}}]}")
                .error().isPresent(), "two visuals");
        check(helper, parse("{\"nodes\": [{\"id\": \"a\", \"pos\": [1, 2]}]}").error().isPresent(), "a typo is an error, not ignored");
        check(helper, parse("{\"camera\": {\"fov\": \"wide\"}}").error().isPresent(), "bad camera value");
        String misspelt = parse("{\"interface\": {\"carry_lfit\": 3}}").error().map(Object::toString).orElse("");
        check(helper, misspelt.contains("carry_lfit") && misspelt.contains("did you mean \"carry_lift\""),
                "an unknown field is an error naming the closest one: " + misspelt);
        check(helper, parse("{\"nodes\": [{\"id\": \"a\", \"grabbable\": {\"lfit\": 3}}]}").error().isPresent(), "unknown nested field");
        check(helper, parse("{\"$schema\": \"x\", \"nodes\": [{\"id\": \"a\", \"$comment\": \"note\"}]}").error().isEmpty(),
                "$ keys are allowed");
        SceneDefinition shelf = parse("{\"inventory\": {\"pos\": [8, 16, -6], \"spacing\": 2.4,"
                + " \"surface\": \"minecraft:oak_planks\"}}").result().orElseThrow();
        InventoryLayout layout = shelf.inventory().orElseThrow();
        check(helper, layout.slotOffset(0).z < layout.slotOffset(27).z && layout.slotOffset(27).z < layout.slotOffset(9).z
                && layout.slotOffset(0).x > layout.slotOffset(8).x, "hotbar nearest the player, slot 9 farthest, column 0 on the left");

        // Node switches, two-sided glass, light offsets, gliding controls and the inventory shot.
        SceneDefinition extras = parse("""
                {"camera": {"inventory": {"position": [8, 30, -10], "target": [8, 12, 0], "fov": 60}},
                 "nodes": [
                   {"id": "glass", "tooltip": "mymod.glass", "visual": {"model": "x:y", "double_sided": true},
                    "light": {"offset": [4, 0, 2]},
                    "children": [{"id": "label", "visual": {"model": "x:z"}, "pickable": false}]},
                   {"id": "needle", "control": {"control": "heat", "type": "knob", "smoothing": 0.3}}
                 ]}""").result().orElseThrow();
        NodeDefinition glass = extras.nodes().get(0);
        check(helper, glass.tooltip().orElse("").equals("mymod.glass") && glass.pickable(), "tooltip read, pickable by default");
        check(helper, glass.visual().orElseThrow().doubleSided(), "double_sided read");
        check(helper, glass.light().orElseThrow().offset().x() == 4.0F, "light offset read");
        check(helper, !glass.children().get(0).pickable(), "pickable false read");
        check(helper, extras.nodes().get(1).control().orElseThrow().smoothing() == 0.3F, "control smoothing read");
        check(helper, extras.camera().inventory().orElseThrow().fov() == 60.0F, "inventory shot read");
        check(helper, parse("{\"nodes\": [{\"id\": \"a\", \"pickable\": false, \"grabbable\": {}}]}").error().isPresent(),
                "a node that cannot be picked cannot have a role");

        // The inventory drawer, pouring looks, clear glass and the ways of holding an object.
        SceneDefinition drawer = parse("""
                {"inventory_drawer": {"side": "left", "align": 0.25, "icon": "minecraft:chest", "style": "minimal", "hover_zone": 0},
                 "pouring": {"hints": false, "stream_width": 2},
                 "nodes": [{"id": "vial", "visual": {"model": "x:y", "glass": 0.7},
                   "grabbable": {"hold": "click"},
                   "tank": {"tank": "t", "inner": {"from": [0, 0, 0], "to": [2, 2, 2]}, "spout": [1, 3, 1], "pour_height": 5}}]}""")
                .result().orElseThrow();
        InventoryDrawer read = drawer.inventoryDrawer().orElseThrow();
        check(helper, read.side() == InventoryDrawer.Side.LEFT && read.align() == 0.25F && read.style() == InventoryDrawer.Style.MINIMAL
                && read.hoverZone() == 0.0F && read.icon() == Items.CHEST, "drawer read");
        check(helper, InventoryDrawer.DEFAULT.side() == InventoryDrawer.Side.RIGHT && !read.side().equals(InventoryDrawer.Side.RIGHT)
                && read.side().isHorizontal(), "drawer defaults");
        check(helper, !drawer.pouring().hints() && drawer.pouring().streamWidth() == 2.0F && drawer.pouring().meter(), "pouring read");
        NodeDefinition vial = drawer.nodes().get(0);
        check(helper, vial.visual().orElseThrow().glass() == 0.7F && vial.visual().orElseThrow().translucent(), "glass read, and see-through");
        check(helper, vial.grabbable().orElseThrow().hold() == Grabbable.Hold.CLICK, "hold read");
        check(helper, vial.tank().orElseThrow().pourHeight() == 5.0F, "pour height read");
        DataResult<SceneDefinition> glances = parse("""
                {"camera": {"glances": [
                   {"id": "inventory", "edge": "bottom", "position": [8, 30, -16], "target": [8, 15, -5], "fov": 60, "back_on_take": false},
                   {"id": "shelf", "position": [8, 40, 0], "target": [8, 30, 16], "hint_color": "#80FFD000"}]},
                 "inventory_drawer": {"side": "right"}}""");
        check(helper, glances.error().isEmpty(), "glances parse: " + glances.error().map(Object::toString).orElse(""));
        glances.result().ifPresent(parsedScene -> {
            CameraRig rig = parsedScene.camera();
            check(helper, rig.glance("inventory").flatMap(CameraGlance::edge).orElse(null) == InventoryDrawer.Side.BOTTOM, "glance edge");
            check(helper, !rig.glance("inventory").orElseThrow().backOnTake(), "glance keeps the camera on a take");
            check(helper, rig.glance("shelf").orElseThrow().edge().isEmpty(), "a glance without an edge is for code");
        });
        String twice = parse("""
                {"camera": {"glances": [{"id": "a", "edge": "left", "position": [0, 0, 0], "target": [0, 0, 1]},
                                        {"id": "b", "edge": "left", "position": [0, 0, 0], "target": [0, 0, 1]}]}}""")
                .error().map(Object::toString).orElse("");
        check(helper, twice.contains("edge"), "one glance per edge: " + twice);
        String drawerEdge = parse("""
                {"camera": {"glances": [{"id": "a", "edge": "right", "position": [0, 0, 0], "target": [0, 0, 1]}]},
                 "inventory_drawer": {"side": "right"}}""").error().map(Object::toString).orElse("");
        check(helper, drawerEdge.contains("edge"), "the drawer's edge is taken: " + drawerEdge);
        check(helper, parse("""
                {"camera": {"glances": [{"id": "A b", "position": [0, 0, 0], "target": [0, 0, 1]}]}}""").error().isPresent(),
                "glance ids are like node ids");
        String both = parse("{\"inventory\": {\"pos\": [8, 16, -6]}, \"inventory_drawer\": {}}").error().map(Object::toString).orElse("");
        check(helper, both.contains("inventory_drawer"), "one inventory per scene: " + both);
        check(helper, parse("{\"inventory_drawer\": {\"side\": \"middle\"}}").error().isPresent(), "no such side");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void tippingKeepsTheLiquid(GameTestHelper helper) {
        check(helper, LiquidVolume.level(3, 5, 3, 0, 1, 0, 0.5F) == 0.0F, "half full upright: the middle");
        check(helper, LiquidVolume.level(3, 5, 3, 0, 1, 0, 1.0F) == 5.0F, "full upright: the top");
        for (float degrees = 5.0F; degrees <= 175.0F; degrees += 17.0F) {
            float x = (float) Math.sin(Math.toRadians(degrees));
            float y = (float) Math.cos(Math.toRadians(degrees));
            for (float share = 0.05F; share < 1.0F; share += 0.15F) {
                float level = LiquidVolume.level(3, 5, 2, x, y, 0.0F, share);
                float back = LiquidVolume.shareBelow(3, 5, 2, x, y, 0.0F, level);
                check(helper, Math.abs(back - share) < 0.002F, "volume kept at " + degrees + " degrees: " + share + " -> " + back);
            }
        }
        // Tipped, more liquid stands higher, and a level never leaves the box.
        float sin = (float) Math.sin(Math.toRadians(25.0));
        float cos = (float) Math.cos(Math.toRadians(25.0));
        float high = LiquidVolume.level(3, 5, 3, sin, cos, 0.0F, 0.95F);
        float low = LiquidVolume.level(3, 5, 3, sin, cos, 0.0F, 0.5F);
        check(helper, low < high && high <= sin * 3.0F + cos * 5.0F, "levels in order: " + low + " < " + high);
        helper.succeed();
    }

    @GameTest(structure = "diegesis:empty")
    public void nodeTemplatesExpand(GameTestHelper helper) {
        Map<Identifier, JsonObject> templates = new HashMap<>();
        for (String name : List.of("lever", "switch", "button", "knob", "slider")) {
            String file = "/assets/diegesis/" + NodeTemplates.DIR + "/control/" + name + ".json";
            try (InputStream in = DiegesisMod.class.getResourceAsStream(file)) {
                check(helper, in != null, "built-in template " + file);
                templates.put(DiegesisMod.id("control/" + name), JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        templates.put(Identifier.fromNamespaceAndPath("mymod", "big_knob"), JsonParser.parseString(
                "{\"template\": \"diegesis:control/knob\", \"scale\": 2, \"control\": {\"range\": [90, -90]}}").getAsJsonObject());
        templates.put(Identifier.fromNamespaceAndPath("mymod", "loop"),
                JsonParser.parseString("{\"template\": \"mymod:loop\"}").getAsJsonObject());

        JsonObject grid = NodeTemplates.expand(JsonParser.parseString("{\"nodes\": [{\"id\": \"mark\", \"pos\": [10, 5, 0],"
                + " \"repeat\": {\"count\": [2, 1, 3], \"step\": [-3, 0, 4]}, \"children\": [{\"id\": \"dot\"}]}]}"), templates::get)
                .getOrThrow().getAsJsonObject();
        var cells = grid.getAsJsonArray("nodes");
        check(helper, cells.size() == 6, "repeat makes one node per cell: " + cells.size());
        JsonObject last = cells.get(5).getAsJsonObject();
        check(helper, last.get("id").getAsString().equals("mark_5") && !last.has("repeat"), "ids count on: " + last);
        check(helper, last.getAsJsonArray("pos").get(0).getAsFloat() == 7.0F && last.getAsJsonArray("pos").get(2).getAsFloat() == 8.0F,
                "cells step from the node's place: " + last.get("pos"));
        check(helper, last.getAsJsonArray("children").get(0).getAsJsonObject().get("id").getAsString().equals("dot_5"), "children follow");
        check(helper, NodeTemplates.expand(JsonParser.parseString("{\"nodes\": [{\"id\": \"a\", \"repeat\": {\"count\": [2, 1, 1]}}]}"),
                templates::get).error().isPresent(), "repeat needs a step");

        StringBuilder nodes = new StringBuilder();
        for (String name : List.of("lever", "switch", "button", "knob", "slider")) {
            nodes.append("{\"id\": \"").append(name).append("\", \"template\": \"diegesis:control/").append(name)
                    .append("\", \"pos\": [8, 16, 8], \"control\": {\"control\": \"c_").append(name).append("\"}},");
        }
        nodes.append("{\"id\": \"gas\", \"template\": \"mymod:big_knob\", \"control\": {\"control\": \"gas\", \"base\": null},"
                + " \"children\": [{\"id\": \"inner\", \"template\": \"diegesis:control/button\", \"control\": {\"control\": \"x\"}}]}");
        DataResult<SceneDefinition> parsed = expand("{\"nodes\": [" + nodes + "]}", templates);
        check(helper, parsed.error().isEmpty(), "templated scene parses: " + parsed.error().map(Object::toString).orElse(""));
        SceneDefinition scene = parsed.result().orElseThrow();
        for (NodeDefinition node : scene.nodes().subList(0, 5)) {
            ControlBinding control = node.control().orElseThrow();
            check(helper, control.control().equals("c_" + node.id()), "the scene names the control of " + node.id());
            check(helper, control.base().flatMap(Visual::model).map(id -> id.getPath().endsWith(node.id() + "_base")).orElse(false),
                    node.id() + " keeps the template's base");
            check(helper, node.transform().pos().y() == 16.0F, "the scene's pos wins for " + node.id());
        }
        check(helper, scene.nodes().get(0).control().orElseThrow().pivot().y() == 3.5F, "lever turns round its hinge");
        NodeDefinition gas = scene.nodes().get(5);
        check(helper, gas.transform().scale().x() == 2.0F && gas.control().orElseThrow().range()[0] == 90.0F,
                "a template built on a template");
        check(helper, gas.control().orElseThrow().base().isEmpty(), "null removes a field of the template");
        check(helper, gas.children().get(0).control().orElseThrow().type() == ControlBinding.Type.BUTTON, "children are expanded");

        check(helper, expand("{\"nodes\": [{\"id\": \"a\", \"template\": \"mymod:nothing\"}]}", templates).error().isPresent(),
                "an unknown template is an error");
        check(helper, expand("{\"nodes\": [{\"id\": \"a\", \"template\": \"mymod:loop\"}]}", templates).error().isPresent(),
                "a template that starts from itself is an error");
        helper.succeed();
    }

    private static DataResult<SceneDefinition> expand(String json, Map<Identifier, JsonObject> templates) {
        return NodeTemplates.expand(JsonParser.parseString(json), templates::get)
                .flatMap(expanded -> SceneDefinition.CODEC.parse(JsonOps.INSTANCE, expanded));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sessions
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(structure = "diegesis:box")
    public void sessionReturnsItemsOnClose(GameTestHelper helper) {
        testStation();
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.LODESTONE);
        ServerPlayer player = mockPlayer(helper);
        try {
            Vec3 standing = Vec3.atBottomCenterOf(helper.absolutePos(pos.north()));
            player.teleportTo(standing.x, standing.y, standing.z);
            StationManager manager = StationManager.get(helper.getLevel().getServer());
            check(helper, manager.openWithoutChannelCheck(player, helper.absolutePos(pos)), "opened");
            StationContext station = Diegesis.activeStation(helper.getLevel(), helper.absolutePos(pos)).orElseThrow();
            check(helper, station.facing() == Direction.NORTH, "turned towards the player: " + station.facing());
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 9));
            manager.handle(player, new Packets.Action(helper.absolutePos(pos), new StationAction.Click(inv(0), 0, ClickKind.PICKUP)));
            manager.handle(player, new Packets.Action(helper.absolutePos(pos), new StationAction.Click(grid(1), 1, ClickKind.PICKUP)));
            check(helper, station.state().getItem(SlotRef.of(GRID, 1)).getCount() == 1 && carried(player).getCount() == 8,
                    "one placed through the manager, eight on the cursor");
            // An action for another block is ignored.
            manager.handle(player, new Packets.Action(helper.absolutePos(pos).above(),
                    new StationAction.Click(grid(1), 0, ClickKind.PICKUP)));
            check(helper, station.state().getItem(SlotRef.of(GRID, 1)).getCount() == 1, "wrong position ignored");
            manager.close(player, false);
            check(helper, player.getInventory().countItem(Items.EMERALD) == 9 && carried(player).isEmpty(),
                    "slot items and the cursor stack returned on close");
            check(helper, Diegesis.activeStation(helper.getLevel(), helper.absolutePos(pos)).isEmpty(), "station ended");
        } finally {
            remove(player);
        }
        helper.succeed();
    }

    @GameTest(structure = "diegesis:box")
    public void walkingAwayOrLoggingOutGivesItemsBack(GameTestHelper helper) {
        testStation();
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, Blocks.LODESTONE);
        ServerPlayer player = mockPlayer(helper);
        Vec3 standing = Vec3.atBottomCenterOf(helper.absolutePos(pos.south()));
        player.teleportTo(standing.x, standing.y, standing.z);
        StationManager manager = StationManager.get(helper.getLevel().getServer());
        check(helper, manager.openWithoutChannelCheck(player, helper.absolutePos(pos)), "opened");
        player.getInventory().setItem(0, new ItemStack(Items.APPLE, 4));
        place(manager, player, helper.absolutePos(pos), 0);
        player.teleportTo(standing.x + 40.0, standing.y, standing.z);
        manager.tick();
        check(helper, Diegesis.activeStation(helper.getLevel(), helper.absolutePos(pos)).isEmpty(), "closed when out of reach");
        check(helper, player.getInventory().countItem(Items.APPLE) == 4, "items back with the player");

        // A player who logs out mid-use: the items go back before the player is saved.
        player.teleportTo(standing.x, standing.y, standing.z);
        check(helper, manager.openWithoutChannelCheck(player, helper.absolutePos(pos)), "reopened");
        place(manager, player, helper.absolutePos(pos), 2);
        check(helper, player.getInventory().countItem(Items.APPLE) == 0, "apples on the station");
        remove(player);
        check(helper, player.getInventory().countItem(Items.APPLE) == 4, "apples back at logout");
        check(helper, Diegesis.activeStation(helper.getLevel(), helper.absolutePos(pos)).isEmpty(), "station ended at logout");
        helper.succeed();
    }

    @GameTest(structure = "diegesis:box", maxTicks = 200)
    public void manyStationsStayCheap(GameTestHelper helper) {
        testStation();
        StationManager manager = StationManager.get(helper.getLevel().getServer());
        List<ServerPlayer> players = new ArrayList<>();
        List<BlockPos> blocks = new ArrayList<>();
        // 16 stations, each used by its own player who keeps moving items and turning the dial.
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                BlockPos pos = new BlockPos(1 + x * 1, 1, 1 + z * 1);
                helper.setBlock(pos, Blocks.LODESTONE);
                ServerPlayer player = mockPlayer(helper);
                Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(pos.above(2)));
                player.teleportTo(at.x, at.y, at.z);
                check(helper, manager.openWithoutChannelCheck(player, helper.absolutePos(pos)), "opened " + pos);
                player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                players.add(player);
                blocks.add(helper.absolutePos(pos));
            }
        }
        long started = System.nanoTime();
        // Within the per-player action budget (40 a second), so every action is really applied.
        int ticks = 40;
        for (int tick = 0; tick < ticks; tick++) {
            for (int i = 0; i < players.size(); i++) {
                ServerPlayer player = players.get(i);
                BlockPos block = blocks.get(i);
                StationAction action = switch (tick % 5) {
                    case 0 -> new StationAction.Click(inv(0), 1, ClickKind.PICKUP);
                    case 1 -> new StationAction.Click(grid(tick % 3), 1, ClickKind.PICKUP);
                    case 2 -> new StationAction.Spread(List.of(grid(0), grid(1), grid(2), grid(3)), 0);
                    case 3 -> new StationAction.SetControl(DIAL, tick % 10);
                    default -> new StationAction.Click(grid(3), 0, ClickKind.QUICK_MOVE);
                };
                manager.handle(player, new Packets.Action(block, action));
            }
            manager.tick();
        }
        double perTickMs = (System.nanoTime() - started) / 1.0e6 / ticks;
        DiegesisMod.LOGGER.info("Load test: {} stations, {} ms per server tick", players.size(), String.format("%.3f", perTickMs));
        check(helper, manager.activeCount() >= 16, "all stations alive");
        int ingots = players.stream().mapToInt(player -> player.getInventory().countItem(Items.IRON_INGOT)
                + carried(player).getCount()).sum();
        players.forEach(player -> manager.close(player, false));
        int back = players.stream().mapToInt(player -> player.getInventory().countItem(Items.IRON_INGOT)).sum();
        players.forEach(ModGameTests::remove);
        check(helper, back == 16 * 64, "not one ingot lost or made: " + back + " (before close " + ingots + ")");
        check(helper, perTickMs < 5.0, "16 busy stations cost " + perTickMs + " ms per tick");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private static Target grid(int index) {
        return new Target.Station(SlotRef.of(GRID, index));
    }

    private static Target fuel() {
        return new Target.Station(SlotRef.of(FUEL));
    }

    private static Target inv(int index) {
        return new Target.Inventory(index);
    }

    private static ItemStack carried(Player player) {
        return player.inventoryMenu.getCarried();
    }

    private static boolean click(TestContext station, Player player, Target target, int button) {
        return ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Click(target, button, ClickKind.PICKUP));
    }

    private static boolean quick(TestContext station, Player player, Target target) {
        return ActionHandler.apply(station, StationLogic.NONE, player, new StationAction.Click(target, 0, ClickKind.QUICK_MOVE));
    }

    /** Everything of an item: in the station, in the inventory and on the cursor. */
    private static int total(TestContext station, Player player, net.minecraft.world.item.Item item) {
        int count = player.getInventory().countItem(item) + (carried(player).is(item) ? carried(player).getCount() : 0);
        for (int i = 0; i < 4; i++) {
            ItemStack stack = station.state.getItem(SlotRef.of(GRID, i));
            count += stack.is(item) ? stack.getCount() : 0;
        }
        ItemStack fuel = station.state.getItem(SlotRef.of(FUEL));
        return count + (fuel.is(item) ? fuel.getCount() : 0);
    }

    /** Picks up inventory slot 0 and puts the whole stack into grid[index], through the manager. */
    private static void place(StationManager manager, ServerPlayer player, BlockPos pos, int index) {
        manager.handle(player, new Packets.Action(pos, new StationAction.Click(inv(0), 0, ClickKind.PICKUP)));
        manager.handle(player, new Packets.Action(pos, new StationAction.Click(grid(index), 0, ClickKind.PICKUP)));
    }

    private static DataResult<SceneDefinition> parse(String json) {
        return SceneDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static int count(TestContext station, int slot) {
        return station.state.getItem(SlotRef.of(GRID, slot)).getCount();
    }

    /**
     * A server player standing in the test level, not in the player list. {@code makeMockServerPlayerInLevel} puts it in the
     * player list and at the world spawn; this one stays out of everything and queues what it is sent.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "diegesis-test");
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile,
                ClientInformation.createDefault()) {
            @Override
            public boolean isSpectator() {
                return false;
            }
        };
        new ServerGamePacketListenerImpl(helper.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false));
        return player;
    }

    private static RegistryAccess registries(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    /** What the loaders do when the player logs out (before the player is saved). */
    private static void remove(ServerPlayer player) {
        DiegesisMod.onPlayerLogout(player);
    }

    private static void check(GameTestHelper helper, boolean condition, String what) {
        if (!condition) {
            helper.fail(what);
        }
    }

    /** A station without blocks or players around it, for the action checks. */
    private static final class TestContext implements StationContext {
        private final Level level;
        private final StationState state = new StationState(SCHEMA);

        TestContext(Level level) {
            this.level = level;
        }

        @Override
        public StationType type() {
            return testStation();
        }

        @Override
        public Level level() {
            return level;
        }

        @Override
        public BlockPos pos() {
            return BlockPos.ZERO;
        }

        @Override
        public BlockState blockState() {
            return Blocks.LODESTONE.defaultBlockState();
        }

        @Override
        public Direction facing() {
            return Direction.NORTH;
        }

        @Override
        public StationState state() {
            return state;
        }

        @Override
        public Collection<? extends Player> users() {
            return List.of();
        }

        @Override
        public void effect(Identifier effect, String node, CompoundTag data) {
        }
    }

    /** Offers a copy of grid[0] while it holds two or more; taking it uses two. */
    private static final class TestLogic implements StationLogic {
        @Override
        public void updateOutputs(StationContext station) {
            ItemStack first = station.state().getItem(SlotRef.of(GRID, 0));
            station.state().setOutput(RESULT, first.getCount() >= 2 ? first.copyWithCount(1) : ItemStack.EMPTY);
        }

        @Override
        public ItemStack takeOutput(StationContext station, Player player, String output) {
            ItemStack first = station.state().getItem(SlotRef.of(GRID, 0));
            if (first.getCount() < 2) {
                return ItemStack.EMPTY;
            }
            station.state().setItem(SlotRef.of(GRID, 0), first.copyWithCount(first.getCount() - 2));
            return first.copyWithCount(1);
        }
    }
}

