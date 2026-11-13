# Stations

A station type binds an interface to blocks and declares the state it keeps and the rules it follows. It is code that
runs on **both sides**: build it with `Diegesis.station(id)` and register it with `Diegesis.register(type)` (or
`.register()` on the builder) during mod setup, the same on the client and the server.

```java
public static final StationType STILL = Diegesis.station(ResourceLocation.fromNamespaceAndPath("mymod", "still"))
        .block(MyBlocks.STILL)
        .schema(StationSchema.builder()
                .slots("input", 2, SlotRules.DEFAULT.maxStack(16).accepts(stack -> stack.is(MyTags.DISTILLABLE)))
                .slots("bottles", 3, SlotRules.DEFAULT.kept())
                .tank("boiler", TankSpec.of(1000).accepts(liquid -> liquid.equals(WATER)).kept())
                .control("heat", ControlSpec.stepped(0, 3, 3, 0))
                .control("lit", ControlSpec.toggle(false).locked())
                .control("start", ControlSpec.button())
                .output("spirit")
                .build())
        .logic(StillLogic::new)
        .build();
```

## Builder

| Method | Default | Meaning |
|---|---|---|
| `blocks(Block...)` | - | Blocks that open the station. |
| `block(Supplier<? extends Block>)` | - | A block looked up when first needed: a NeoForge `DeferredBlock`, a Fabric field filled in `onInitialize`. |
| `tag(TagKey<Block>)` | - | Every block in the tag opens it. |
| `when(Predicate<BlockState>)` | all states | Only some states open it (the lower half of a tall block). |
| `anchor(Anchor)` | every block alone | Multiblocks: which block the station lives at (below). |
| `authority(Authority)` / `clientOnly()` | `SERVER` | Who owns the state (below). |
| `schema(StationSchema)` | empty | Slots, tanks, controls, outputs. |
| `scene(ResourceLocation)` | the station id | Another scene than `assets/<ns>/diegesis/scenes/<path>.json` of the id. |
| `logic(StationLogic.Factory)` | none | The rules; one logic object per station in use. |
| `openWhen(OpenCondition)` | vanilla-like | `(player, pos, state)`: whether a use opens the station; false leaves the block its normal use. The default lets sneaking with an item in hand use the item. |
| `orientation(Orientation)` | `AUTO` | How the scene is turned: `AUTO` (the block's facing if it has one, else towards the player), `BLOCK`, `PLAYER`, `FIXED` (as authored). |
| `reach(double)` | `6` | How far (blocks) from the eyes to the block centre a player may work. |
| `exclusive()` | shared | One player at a time; others get the block's normal use while it is busy. |
| `hidesBlock()` | off | The block's own model is not drawn while the station is in use: the scene shows it instead, so its parts can open and move. Any block, also another mod's. |
| `idleLogic()` | off | Shows the block's real state in an `idle_visible` scene while nobody works at the station, with no traffic: every client near it runs its own copy of the logic's `tick`. For blocks that are no `StationHost` but send their content to clients anyway; the logic binds the slots as on the server and only reads and sets the state there. |

At least one block, supplier or tag is required.

## Authority: client or server

- **`SERVER`** (default): the server checks and applies every action, items move safely between the player and the
  station, every player sees the same state, and the players around see what each user carries. The server needs
  Diegesis; on a server without it the block works as it does without the library.
- **`CLIENT`** (`clientOnly()`): the client alone. It works on any server, also one without Diegesis, but nobody else
  sees what the player does and no items can move (a client-only station cannot have slots or outputs). Use it for
  visual stations, previews and animations: a furnace that shows its fire up close, a toy. Its `StationLogic` runs on
  the client.

## Schema

The schema is the state the scene binds to by name. Names are lower case letters, digits and `_`, unique across slot
groups, tanks, controls and outputs.

- **Slot groups** `slots(name, count[, rules])` - `count` slots, `name[0]` .. `name[count - 1]`.
  `SlotRules.DEFAULT` takes any item in full stacks and gives it back when the last player leaves.
  `.maxStack(n)`, `.accepts(predicate)`, `.kept()` (the items stay in the block; needs a `StationHost`), `.placed()` (the
  items are put down by hand on the surfaces of the scene's [`placing`](scenes.md#placing); shift-clicks and double clicks in
  the inventory pass these slots by). `StationState.getPose(slot)` says where such an item stands, `setPose` moves it.
- **Tanks** `tank(name, TankSpec)` - liquids in millibuckets. `TankSpec.of(capacity)`, `.accepts(id -> ...)`,
  `.pourRate(mbPerSecond)` (the fastest a player may pour; checked by the server), `.kept()`,
  `.filledWith(liquid, amount)` (what a fresh tank holds). A liquid is an id: a fluid's (`minecraft:water`) or any id
  you make up for a reagent. `TankContents` holds one liquid or a mixture.
- **Controls** `control(name, ControlSpec)` - `ControlSpec.range(min, max, initial)` (smooth),
  `stepped(min, max, steps, initial)`, `toggle(on)`, `button()` (no value; each press reaches the logic).
  `.locked()`: players cannot change it, only the logic (`state().setControl`) - a status the scene shows.
- **Outputs** `output(name)` - a result the logic computes, such as a crafting result.

## Logic

`StationLogic` holds the rules. It runs where the state lives (the server for server stations). The library has
already checked an action against the schema (slot exists, item fits the rules, value in range) before it asks the
logic. Every method has a harmless default.

| Method | When |
|---|---|
| `onOpen(station, player)` / `onClose(station, player)` | A player starts / stops working (left, walked away, logged out). |
| `mayPlace(station, player, slot, stack)` / `mayTake(station, player, slot)` | Before an item goes in or out. |
| `onTaken(station, player, slot, stack)` | A player took a stack out of a slot: what a vanilla result slot does on taking (experience, recipes). |
| `updateOutputs(station)` | After every slot change and when the station opens: compute outputs, `state().setOutput(name, stack)`. |
| `takeOutput(station, player, output)` | A player takes an output: consume its cost, return the item (or `ItemStack.EMPTY`). `updateOutputs` runs after. |
| `onSlotChanged(station, slot)` | A slot's item changed. |
| `onControlChanged(station, player, control, value)` | A player set a control (value already clamped). |
| `onButton(station, player, control)` | A player pressed a button. |
| `mayPour(station, player, from, to, liquid)` / `onPoured(...)` | Pouring between tanks: allow it, then react (mix with `state().setTank`). |
| `onCustomAction(station, player, action, data)` | A mod-defined action from `StationView.sendAction` on the client. Check `data` like any input. |
| `tick(station)` | Every tick while at least one player works at the station. |

A logic object lives while the station is in use; anything that must outlive it belongs in the `StationState` of a
`StationHost`.

`StationContext` gives the logic `type()`, `level()`, `pos()`, `blockState()`, `facing()`, `state()`, `users()`,
`blockEntity()` and `effect(id, node[, data])`: plays an effect for everyone who sees the station. Each built-in
particle style (`DiegesisParticles.SPARK` ...) is also an effect - a burst at the node (`data`: `count`, `color`).

## Keeping state in the block: StationHost

Without a host, the state lives only while players work at the station: kept slots or tanks need one. Let the block
entity implement `StationHost` and save the state:

```java
public class StillBlockEntity extends BlockEntity implements StationHost {
    private final StationState station = new StationState(MyStations.STILL.schema());

    @Override
    public StationState stationState() {
        return station;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("station", station.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        station.load(tag.getCompound("station"), registries);
    }

    // Clients see the items on the block while nobody works at it (idle_visible scenes).
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
```

The library marks the block entity changed and sends the block update whenever a player changes the state. Drop the
kept items in your block's `onRemove` (`station.getItems(group)`).

`kept()` decides what lasts between uses, with a host too: when the last player leaves, slots that are not kept give
their items back and tanks that are not kept start over from their `filledWith` contents (a water bottle that is full
again next time). Controls keep their values; set them in `onClose` if they should not (turn the gas off).

### Work that goes on while nobody is at the block

`StationLogic.tick` runs only while someone works at the station. A machine that keeps brewing after the player walks
away ticks in its block entity and changes the host's state; the library syncs it to everyone watching:

```java
public static void serverTick(Level level, BlockPos pos, BlockState state, StillBlockEntity still) {
    if (Diegesis.activeStation((ServerLevel) level, pos).isPresent()) {
        return; // the station's logic ticks while it is in use
    }
    boolean changed = brew(still.stationState()); // your own work on tanks, slots or controls
    if (changed) {
        still.setChanged();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS); // idle viewers get the update tag
    }
}
```

## Multiblocks

A station spanning several blocks (a long table) lives at one main block. Give the builder an `Anchor` that finds the
main block from any part; every part then opens the same station there, and the scene is placed and turned from that
block (scene coordinates past 16 reach into the other parts).

```java
.anchor((level, pos, state) -> state.getValue(PART) == Part.MAIN ? pos : pos.relative(state.getValue(FACING).getCounterClockWise()))
```

The main block must match the station too.

## Seats

For stations where it matters who is where: the two sides of a board, the posts of a crew.

```java
.seat(Seat.sitting("white", 16, 8, -8))   // scene pixels: the surface the player sits on
.seat(Seat.sitting("black", 16, 8, 40))   // Seat.at(...) is a place to stand at
```

A player who opens the station takes the free seat nearest to them and sees it through the scene's `camera.seats`. With
`sitting` they sit down there, and everyone around sees them sit; leaving stands them up on the seat. When all seats are
taken, others can still open the station and watch from the rig's own view. The logic asks `context.seatOf(player)` and
`context.playerAt(seat)`, the client `view.seat()`; during `onClose` the player still holds the seat.

## The station's own data

Slots, tanks and controls cover what a player touches. For anything else (a board position, a score, a list of what has
been found) a station has one `CompoundTag`:

```java
CompoundTag data = context.state().data();   // a copy
data.putString("fen", board.toFen());
context.state().setData(data);               // saved with the state, sent to every client
```

It goes to the clients in one piece on every change, so keep it small. On the client read `view.state().data()` in
`onStateChanged` (`changes.data()` is true) and in `onSceneReady`. With a `StationHost` it stays after everyone left, and
passers-by get it through the block entity's update tag.

## Stations on other mods' blocks

A station can take over a block of another mod without touching it: name the block, and its use opens the station instead
of its menu. The block entity stays the owner of its items, so it goes on working, hoppers reach it and it drops them when
broken. Bind the slot groups to its inventory in the logic's constructor:

```java
public PotLogic(StationContext station) {
    Container pot = (Container) station.blockEntity();
    station.state().bindSlots("ingredients", SlotStorage.of(pot, 0, 6));
}
```

`SlotStorage.of` wraps a vanilla `Container`; for anything else implement `size`, `get` and `set` (a NeoForge `IItemHandler` is
three lines). A bound group reads and writes the storage directly, what changes there by itself reaches the players on the next
tick, and `save(registries)` leaves it out. Declare such groups `kept()`: nothing is handed back on close.

Values the block's menu shows (progress, heat) go into locked controls or the station's data from `tick`. With
`hidesBlock()` the scene draws the block itself, so its parts can move: rebuild the block's shape from its model, read its
state in `onSceneReady`, show the copy while `StationView.isInUse()` and turn it when the station faces another way than the
block. A player without your
mod, or on a server without it, keeps the block's menu.

## Opening from code

`Diegesis.open(serverPlayer, pos)` opens the station at `pos` from server code (for example from your block's `use`
when you decide yourself), returning false when the block is not a station, the player is too far or their client
lacks Diegesis - then show your usual GUI. `Diegesis.close(player)` ends their work; `Diegesis.activeStation(level,
pos)` is the station in use there, if any.

## What the library guarantees

- Item moves are atomic and checked on the server: no duplication with lag, double clicks, or several players at once.
- Clients may send at most 40 actions a second; more are dropped.
- Session items (slots that are not kept) go back to the player on the last close, at logout (before the player is
  saved), or drop at the block when the player is gone.
- Pouring is paced by the server at the tanks' pour rate, whatever the client sends.
