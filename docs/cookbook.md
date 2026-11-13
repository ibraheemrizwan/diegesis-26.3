# Cookbook

Short recipes for common interfaces. Each shows the schema (Java) and the scene nodes (JSON).

## A board game for two, or against the computer

Chess on a table: two seats, the position in the station's data, pieces as meshes. The seat
gives a player their colour and their side of the board; the logic moves for a side nobody sits at.

```java
.schema(StationSchema.builder().control("level", ControlSpec.stepped(0, 2, 2, 1)).control("new_game", ControlSpec.button()).build())
.seat(Seat.sitting("white", 16, 8, -8))
.seat(Seat.sitting("black", 16, 8, 40))
```

```json
{"id": "white_pawn", "template": "mymod:chess/white_pawn", "pos": [26.5, 14.6, 8.5], "repeat": {"count": [8, 1, 1], "step": [-3, 0, 0]}}
```

The server judges moves (`onCustomAction`) and writes the game with `setData`. The client hops every node to where the data
says it belongs, so a move looks the same for both players and for passers-by. A click picks a piece and a click on a square
plays it: pieces and squares are nodes without a role (`SceneEvent.Click`), with `"precise": true` on the pieces; the client
runs the same rules to mark legal squares and shows a see-through copy on the one under the cursor. A move search takes time: run it off the server thread and pick the result up in `tick`.

## A shelf for anything

Any item, anywhere on the boards, turned and sized by hand, for everyone to see. Three lines of Java and one scene section:

```java
.schema(StationSchema.builder()
        .slots("items", 64, SlotRules.DEFAULT.maxStack(1).placed().kept())
        .build())
```

```json
"inventory_drawer": {"side": "right"},
"placing": {
  "group": "items",
  "surfaces": [
    {"from": [2, 2.5], "to": [30, 14.5], "height": 2, "clearance": 8, "aim_height": 7},
    {"from": [2, 2.5], "to": [30, 14.5], "height": 12, "clearance": 8, "aim_height": 7}
  ]
}
```

`maxStack(1)` makes every click put down one object, the rest of the stack stays in the hand. A table seen from above needs no
`aim_height`. The block entity keeps the state as in [A station that keeps its items](#a-station-that-keeps-its-items) and drops
the items when it is broken.

## Something shaped by hand

A zone that takes strokes, and a shape rebuilt while the mouse works on it. Keep the data in a class without client code, so that
the server and items can hold it, and turn it into a `MeshShape` on the client. Let the camera look at it from the front, so that
what the player draws is its outline; `HOVER` shows the spot before the press.

```json
{"id": "shape", "pos": [8, 13, 9], "pickable": false},
{"id": "work_zone", "pos": [8, 13, 9], "strokes": true, "collider": {"from": [-5.4, -0.5, -5], "to": [5.4, 11, 5]}}
```

```java
public void onEvent(StationView view, SceneEvent event) {
    if (event instanceof SceneEvent.Stroke stroke && stroke.phase() == SceneEvent.Stroke.Phase.MOVE) {
        shape.draw(stroke.point().y() - baseY, Math.abs(stroke.point().x() - axisX), rate * dt);
        view.node("shape").ifPresent(node -> node.setShape(meshOf(shape), TEXTURE));
    }
}
```

The client changes the shape at once and tells the server a few times a second through `sendAction`; the server keeps it in the
station's data for the others. `registerItemShape` makes the finished thing an item that looks the same. What a station needs can
come out of the inventory by itself in `onCustomAction`: no slot, no trip to the inventory.

## Crafting on the block's top

A 3x3 grid and a floating result on a crafting table's top.

```java
.schema(StationSchema.builder().slots("grid", 9).output("result").build())
.logic(MyCraftingLogic::new)   // updateOutputs: look up the recipe; takeOutput: consume one of each, keep remainders
```

```json
{"id": "grid_0", "pos": [11, 16, 11], "slot": {"slot": "grid[0]", "size": 2.7}},
{"id": "result", "pos": [1.6, 16, 8], "output": {"output": "result", "size": 3.4}}
```

Slot centres sit on the cells of the block's texture (5, 8 and 11 pixels for the crafting table). Remember that +X is
the screen's left: `grid[0]`, the top-left cell for the player, is at x = 11.

## A lever that lights a lamp

```java
.control("power", ControlSpec.toggle(false))
```

```json
{"id": "lever", "template": "diegesis:control/lever", "pos": [13, 16, 8], "scale": 0.55, "control": {"control": "power"}},
{"id": "lamp_off", "pos": [8, 16, 11.5], "visual": {"block": "minecraft:redstone_lamp[lit=false]"}, "scale": 0.22},
{"id": "lamp_on", "pos": [8, 16, 11.5], "visual": {"block": "minecraft:redstone_lamp[lit=true]", "emissive": true},
 "scale": 0.22, "hidden": true}
```

```java
@Override
public void onStateChanged(StationView view, StationState.Changes changes) {
    boolean on = view.state().getControl("power") > 0.5F;
    view.node("lamp_on").ifPresent(node -> node.setVisible(on));
    view.node("lamp_off").ifPresent(node -> node.setVisible(!on));
}
```

The lever's value is synced: everyone around sees the lamp change.

## A gauge needle driven by a value

Put the needle on a child of a pivot node and turn the pivot from code every frame:

```json
{"id": "needle_pivot", "pos": [3, 16.1, 13], "children": [
  {"id": "needle", "rotation": [-90, 0, 0], "visual": {"item": "minecraft:arrow", "anchor": [8, 8, 8]}, "scale": 0.22}
]}
```

```java
@Override
public void frame(StationView view, float deltaSeconds) {
    float value = view.state().getControl("pressure");
    view.node("needle_pivot").ifPresent(node -> node.rotateTo(0, 60 - 120 * value, 0, 0));
}
```

A value only the logic sets (progress, pressure) is a `ControlSpec.range(...).locked()`.

## A vial that pours into a flask

```java
.tank("vial", TankSpec.of(250).filledWith(ResourceLocation.withDefaultNamespace("water"), 250))
.tank("flask", TankSpec.of(1000))
```

```json
{"id": "vial", "pos": [4, 16, 10], "visual": {"model": "mymod:diegesis/vial", "glass": 0.8, "double_sided": true},
 "grabbable": {"lift": 6, "grip": [0, 10, 0]},
 "tank": {"tank": "vial", "inner": {"from": [-1, 1, -1], "to": [1, 9, 1]}, "spout": [0, 10, 0], "pour_angle": 120}},
{"id": "flask", "pos": [9, 16, 9], "visual": {"model": "mymod:diegesis/flask", "glass": 0.8, "double_sided": true},
 "tank": {"tank": "flask", "inner": {"from": [-3, 1, -3], "to": [3, 7, 3]}, "opening": [0, 11, 0]}}
```

That is all the pouring there is to write: the player clicks the vial (it stays in the hand) or drags it, and points it at
the flask. The vial settles beside the opening and tips until the liquid reaches the spout; the wheel sets the flow.
`pour_angle` above 90 lets a vial with a neck empty completely. React in `StationLogic.onPoured` (mix: `state().setTank("flask", ...)`). Bubbles in the flask:
`"emitter": {"particle": "diegesis:bubble", "rate": 6, "in_liquid": true}` on the flask node, and
`node.setAgitation(heat)` from the behaviour.

## An inventory that stays out of the way

A busy scene has no room for 36 slots. Put them in a drawer: an icon at the screen's side, the inventory slides out
over the scene when the player points there.

```json
"inventory_drawer": {"side": "right", "style": "minimal", "icon": "minecraft:chest"}
```

To open it from code, for a moment when the player must pick something: `view.setInventoryOpen(true)`.

Or keep the inventory in the world, out of the frame, and let the player look at it: the camera shows the table top, the
cursor at the bottom of the screen glides it down to the slots, and taking a stack glides it back.

```json
"camera": {
  "position": [8, 37, -4], "target": [8, 16, 8], "fov": 55,
  "glances": [{"id": "inventory", "edge": "bottom", "position": [8, 31, -12.2], "target": [8, 15, -9.4]}]
}
```

The same works for anything out of frame: a shelf behind the table on `"edge": "top"`, a gauge the logic wants seen with
`view.glance("gauge")`.

Stations standing side by side need nothing: the cursor at the left or right edge flies the camera over to the next one,
and the player works there without leaving. Keep a station to itself with `"neighbors": {"enabled": false}` in its
`camera`.

## A torch that lights a burner

The torch is a grabbable whose flame is its contact part; the burner's mouth is a trigger zone; a closed carry plane
keeps the torch out of the burner itself.

```json
{"id": "torch", "pos": [3, 16, 12], "visual": {"block": "minecraft:torch"}, "scale": 0.5,
 "collider": {"from": [-1.5, 0, -1.5], "to": [1.5, 11, 1.5]},
 "grabbable": {"lift": 2, "grip": [0, 5, 0], "stay": true, "contact": {"from": [-1, 9, -1], "to": [1, 12, 1]}},
 "light": {"level": 12, "radius": 12}},
{"id": "burner_mouth", "pos": [9, 18, 4], "collider": {"from": [-2, 0, -2], "to": [2, 3, 2]}, "trigger": true}
```

```json
"carry_planes": [{"from": [7, 2], "to": [11, 6], "closed": true, "blend": 1.5, "nodes": ["torch"]}]
```

The behaviour sends `view.sendAction("ignite", ...)` on `SceneEvent.Contact` with `burner_mouth`; the logic checks the
gas and sets the locked `lit` control.

## A station that keeps its items

Slots that are `kept()` (and kept tanks) stay in the block between uses: the block needs a block entity implementing
`StationHost` ([Stations](stations.md#keeping-state-in-the-block-stationhost)). With `"idle_visible": true` in the
scene, everyone sees the items on the block while nobody works at it.

## A client-only toy

No items, no server code: a music box whose crank the player turns, on any server - also one without Diegesis.

```java
Diegesis.station(ResourceLocation.fromNamespaceAndPath("mymod", "music_box")).block(MyBlocks.MUSIC_BOX)
        .clientOnly()
        .schema(StationSchema.builder().control("crank", ControlSpec.range(0, 1, 0)).build())
        .register();
```

Its `StationLogic` (if any) and behaviour run on the client; other players see nothing of it. A station on a block
with a GUI replaces that GUI (the use never reaches the block), so bind client-only stations to blocks of your own or
to blocks without one.
