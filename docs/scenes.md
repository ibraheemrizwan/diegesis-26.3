# Scenes

A scene is everything a station shows and lets the player touch: a camera rig and a tree of nodes. It lives in
`assets/<namespace>/diegesis/scenes/<path>.json`; a station uses the scene with its own id unless
`StationType.Builder.scene(id)` names another. Scenes are resources: **F3+T** reloads them while the game runs, and a
resource pack can restyle them. A scene can also be built in code (`SceneDefinition.builder()`,
`DiegesisClient.registerScene`); a JSON file with the same id still wins.

Machine-readable reference: [`schema/scene.schema.json`](schema/scene.schema.json) (JSON Schema draft-07). Put
`"$schema": "https://raw.githubusercontent.com/Pocky-l/diegesis/main/docs/schema/scene.schema.json"` at the top of a
scene for completion and checks in VS Code, IntelliJ and other editors.

## Rules of the file

- **Units**: positions and sizes are Blockbench pixels; 16 = one block. Angles are degrees.
- **Space**: relative to the block's lower north-west corner, for a block **facing north** - its front is the -Z side,
  as Blockbench shows a block model. `x` 0..16 runs from the block's west to east side, `y` up, `z` from front to back.
  Seen by a player standing in front, +X is on the screen's **left**. The library turns the scene with the block's
  facing (see `orientation` in [Stations](stations.md)). Coordinates past 0..16 reach into neighbouring blocks
  (multiblocks, a camera in front of the block).
- **Strict**: a mistake makes the whole scene fail to load with a message in the log - a wrong value, a missing
  required field, or an unknown field (`Unknown field "lfit" (did you mean "lift"?)`). Keys starting with `$`
  (`"$schema"`, `"$comment"`) are allowed anywhere and ignored.
- **Colours** are `"#RRGGBB"`, `"#AARRGGBB"` or an ARGB integer.
- **Ids** of nodes are lower case letters, digits and `_`, unique in the scene.

## Scene

| Field | Default | Meaning |
|---|---|---|
| `camera` | see below | Where the camera goes while the player works here. |
| `work_plane` | `16` | Height of the surface carried objects and the held stack move over (the table top). |
| `idle_visible` | `true` | Draw the nodes while nobody works at the block. Needs a block entity at the block. |
| `nodes` | `[]` | The top-level nodes. |
| `inventory` | none | Lay the player's inventory out in the scene instead of the screen panel. |
| `inventory_drawer` | none | Keep the inventory in a drawer at a side of the screen. Not together with `inventory`. |
| `clear_view` | see below | What stands between the camera and the station dissolves. |
| `put_away` | `true` | A right click with a stack on the cursor that nothing in the scene takes (no slot, zone or station code) puts the stack back into the inventory, to the slot it came from while that is free. Code: `StationView.putAwayCarried()` does the same, for a station that uses right clicks itself. |
| `interface` | defaults | How the interface behaves and looks. |
| `carry_planes` | `[]` | Levels carried objects rise to over parts of the scene, or areas closed to them. |
| `pouring` | defaults | How pouring looks. |
| `placing` | none | Items put down anywhere on surfaces, see [placing](#placing). |

### camera

| Field | Default | Meaning |
|---|---|---|
| `position` | `[8, 36, -12]` | The camera's position: by default in front of the block, above it. |
| `target` | `[8, 16, 7]` | The point it looks at. |
| `fov` | `0` | Vertical field of view while focused, 0..170; 0 keeps the player's setting. |
| `transition` | `0.6` | Seconds the camera takes to fly in and out. |
| `inventory` | none | A second shot, `{"position", "target", "fov"}`, the camera glides to while the scene's inventory is shown (Tab with `"show": "toggle"`, or an item on the cursor) and back when it hides. Frame the inventory together with the slots it fills, so the laid-out inventory never has to cover the controls. |
| `glances` | `[]` | Other places the player's look can go to, see below. |
| `neighbors` | on | Going over to a station standing next to this one, see below. |

Frame the shot with the debug view on (F3+B): it marks the camera target. A camera much closer than ~16 pixels to the
objects makes carried things look huge.

### camera.glances

A glance moves the look without leaving the station: the rig shows the table top, the cursor rests at the bottom of the
screen, the camera glides down to the inventory, and taking a stack there glides it back.

```json
"camera": {
  "position": [8, 37, -4], "target": [8, 16, 8], "fov": 55,
  "glances": [
    {"id": "inventory", "edge": "bottom", "position": [8, 31, -12.2], "target": [8, 15, -9.4]}
  ]
}
```

| Field | Default | Meaning |
|---|---|---|
| `id` | required | Unique in the scene, `[a-z0-9_]+`. Code and `SceneEvent.Glance` use it. |
| `edge` | none | `left`, `right`, `top` or `bottom`: where the cursor rests to look here. Without one only code does. One glance per edge, and not the edge of the `inventory_drawer`. |
| `zone` | `0.1` | How far from that edge the cursor counts as being at it, as a part of the screen. |
| `delay` | `0.12` | Seconds the cursor must rest there, so that passing by moves nothing. |
| `position`, `target` | required | The camera's place and the point it looks at. |
| `fov` | `0` | Field of view; 0 keeps the rig's. |
| `time` | `0.45` | Seconds the camera takes to get there and back. |
| `back_on_take` | `true` | Look back as soon as a stack comes onto the cursor here. |
| `back_zone` | `0.12` | The strip along the opposite edge that looks back; 0: no strip, only a taken stack or code ends the glance. |
| `hint` | `true` | Draw a small arrow at the edge the look can go to, or come back from. It grows stronger as the cursor comes near. |
| `hint_color` | `#D0FFFFFF` | The arrow's colour; its alpha is the strongest it gets. |

The cursor stays free the whole time: once the camera is there the player moves it over what the glance shows, and only
the opposite edge or a taken stack brings the look back. A stack put down keeps the camera there, so several slots can
be filled in a row. With a stack on the cursor the edge works the same way: that is how it gets back into the inventory.

A cursor still at an edge after coming back has to leave it first. A carried object, a control being turned, a spreading
drag and an open drawer never move the camera. With the screen's inventory panel at the bottom use another edge: the
panel covers that strip.

Client code: `StationView.glance(id)`, `glanceBack()`, `currentGlance()`, `SceneEvent.Glance`. In code:
`rig.withGlance(CameraGlance.at("inventory", Side.BOTTOM, position, target))`.

### gains

What the player gets at the station is seen going into the inventory: a stack that comes into it while they work there,
other than from their own cursor (a soup scooped with a bowl, pieces cut off, a dish taken), flies from the middle of the
station to its slot, which bounces as it lands. When the drawer does not show that slot (a `compact` drawer shows only
what the station uses), the stack stands next to the drawer for a moment. How many came shows as "+N": rising from
the slot as it lands, or beside the stack by the drawer. On by default.

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Gains are shown. |
| `time` | `0.45` | Seconds a stack flies. |
| `hold` | `1.6` | Seconds a stack whose slot is not on show stands next to the drawer. |
| `sound` | `true` | A soft pop as it lands. |
| `count` | `true` | "+N" shows how many came. |

In code: `SceneDefinition.builder().gains(GainOptions.OFF)`.

### camera.neighbors

Stations standing side by side work as one place: the cursor resting at the edge of the screen on the side another
station stands flies the camera over to it, and the player works there without leaving. The edge is the side the
station is on as the rig sees it: left, right, top for one further away, bottom for one behind. Both scenes must allow
it; it is on by default.

```json
"camera": {
  "position": [10, 32, -12], "target": [10, 1, 5.5],
  "neighbors": {"range": 2, "delay": 0.4}
}
```

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Neighbours are reachable from this station, and this station from theirs. |
| `range` | `3` | How far another station may stand, blocks along the floor; one up or down counts too. The nearest one on each edge wins. |
| `zone` | `0.03` | How far from the edge the cursor counts as being at it, as a part of the screen. |
| `delay` | `0.3` | Seconds the cursor must rest there, so that passing by moves nothing. |
| `time` | `0.65` | Seconds the camera takes to fly over. |
| `hint` | `true` | Draw a small arrow at an edge that leads to a neighbour; it shows only as the cursor comes near. |
| `hint_color` | `#D0FFFFFF` | The arrow's colour; its alpha is the strongest it gets. |
| `keys` | `true` | The movement and arrow keys go over at once to the neighbour on their side: A, D and left, right; W and up to the top edge, S and down to the bottom one. Where the cursor is does not matter. |

A station counts when the player could open it from where they stand: its open condition holds and it is within its
reach. An edge used by a glance or by the `inventory_drawer` stays theirs. Nothing moves while a stack is on the cursor,
an object is in the hand or a glance is away, by the cursor or by a key. A cursor still at the edge on arrival has to leave it first, so the camera
never flies on by itself. The server ends the use of the station left as it opens the next one; the cursor stack goes
back to the inventory as on leaving. In code: `rig.withNeighbors(CameraNeighbors.NONE)`.

## Nodes

A node has a place, optionally a look, at most **one role** (grabbable, slot, output, control) and any number of
extras (tank, emitter, light). Children move, turn and scale with their parent - put a second role on a child node.

| Field | Default | Meaning |
|---|---|---|
| `id` | required | Unique in the scene. |
| `template` | none | Start from a node template (below). |
| `pos` | `[0, 0, 0]` | Position relative to the parent, or to the block corner for top-level nodes. |
| `rotation` | `[0, 0, 0]` | Euler degrees, applied y (yaw), then x (pitch), then z (roll). |
| `scale` | `1` | One factor, or `[x, y, z]`. |
| `visual` | none | What is drawn. A node without one is an invisible pivot or hit zone. |
| `collider` | derived | What the cursor can hit: `{"from": [x,y,z], "to": [x,y,z]}` in the node's own pixels. Derived from the visual's real model bounds or from the role when absent. |
| `trigger` | `false` | The collider is only a zone carried objects touch (`SceneEvent.Contact`); the cursor passes through it. |
| `pickable` | `true` | `false`: pure decoration with no hit box at all - the cursor and carried objects pass through it (a gauge needle, a coloured band drawn on a grabbable parent). Not allowed together with a role or `trigger`. |
| `drop_zone` | none | The name of a slot group: a stack clicked or let go on the node goes into it, merging with what is there or into the first empty slot, as into the mouth of a pot. The cursor passes through while the hand is empty or nothing fits, so slots behind it stay reachable. Needs a `collider`, no role. |
| `tooltip` | none | Text next to the cursor while it is over the node: a translation key (shown as written when no language has it). Line breaks (`
`) start new lines. Code changes it with `NodeView.setTooltip(Component)` (a gauge reading, what a vessel holds). A node with a tooltip can be hovered even when it does nothing else. |
| `grabbable` | none | Role: an object the player carries. |
| `slot` | none | Role: shows a station slot's item. |
| `output` | none | Role: shows a station output; clicking takes it. |
| `control` | none | Role: a button, toggle, knob or slider. |
| `tank` | none | Shows a station tank as liquid in the node. |
| `emitter` | none | Gives off particles. |
| `light` | none | Shines on what is near. |
| `hidden` | `false` | Starts invisible; code shows it with `NodeView.setVisible(true)`. |
| `extra` | `{}` | Free-form JSON for your own code: `NodeView.extra()`. |
| `children` | `[]` | Nodes attached to this one. |

Vanilla blocks drawn from planes (torches, flowers, rails) have a full 16-pixel model box: give them an explicit
`collider`, or they catch clicks meant for their neighbours.

What the cursor stops at: the nearest hit box along the cursor's ray among the nodes that can be used (a role, an
explicit `collider`, or a `tooltip`), and the laid-out inventory's slots while it is shown. A node with only a visual
and no explicit collider is never hit by the cursor (but carried objects still touch it), trigger zones never are,
and neither are nodes bound to a locked control unless they have a tooltip. Overlapping boxes: the one the ray enters
first wins, so a slot on a vessel's mouth must stick out above the vessel's own box. While an item is on the cursor only
slots are hit.

### visual

Exactly one of `model`, `item`, `block`.

| Field | Default | Meaning |
|---|---|---|
| `model` | - | A block model id: `mymod:diegesis/flask` = `assets/mymod/models/diegesis/flask.json`. Every model under `models/diegesis/` loads by itself; others need `DiegesisClient.registerModel(id)`. |
| `item` | - | An item id, drawn like an item frame shows it. |
| `block` | - | A block state, as in commands: `"minecraft:lever[face=floor,facing=north]"`. |
| `anchor` | `[8, 0, 8]` | The point of the model (its own pixels) that sits on the node's `pos`. The default, the middle of the model's bottom, makes `pos` the point the object stands on. |
| `tint` | `"#FFFFFFFF"` | Colour multiplied into the model. Alpha below FF makes it see-through. |
| `translucent` | `false` | The textures are see-through (glass): drawn after solid parts. Set by itself for a tint with alpha. |
| `emissive` | `false` | Drawn at full brightness. |
| `double_sided` | `false` | Faces are drawn from behind too, in the same pass: an open glass beaker shows its far walls through the near ones, and the liquid inside stays sorted with them. Block models only. |
| `glass` | `0` | How clear the faces are where they look straight at the camera, 0..1: they thicken towards a grazing angle, as glass does, so what stands behind or inside stays clear. `0.8` suits a vessel. Implies `translucent`. |

Models made in Blockbench ("Java Block/Item" format, exported as JSON) work as they are, with textures from your
mod's or vanilla's block atlas (`"texture": "mymod:block/flask_glass"`). A scale below 1 shrinks a 16-pixel model to
a prop on the table: `"scale": 0.4`.

### grabbable

The player picks the object up with the cursor and carries it. It follows the cursor with a little lag, leans into
its movement and casts a shadow of its shape.

| Field | Default | Meaning |
|---|---|---|
| `lift` | `4` | Height of the grip above the work surface while carried, 0..64. |
| `sway` | `1` | How much it leans while moving, 0..4. |
| `stay` | `false` | Stays where it is dropped instead of flying home. The new place is kept on this client only and is not saved. |
| `grip` | hit box middle | The point it is held by, in the node's pixels: a vial by its top. Tilts pivot round it. |
| `plane` | none | A plane of its own to move in instead of over the work surface: `{"point": [x,y,z], "normal": [0,0,-1], "bounds": {"from": .., "to": ..}}` (scene pixels; the default normal is an upright wall facing the front). Lift and carry planes then do not apply. |
| `contact` | whole hit box | The part that touches other nodes, a box in the node's pixels (a torch's flame). |
| `hold` | `"auto"` | How the hand keeps it. `auto`: a quick click leaves it in the hand until the next click, pressing and dragging carries it until the button is let go. `drag` or `click`: only that way. |

When it is let go, the behaviour gets `SceneEvent.Release(node, target)` with the node under it. While carried, its
`contact` box reports `SceneEvent.Contact` when it starts and stops touching another node's collider.

### carry_planes

Shape where carried objects go. Each entry:

| Field | Default | Meaning |
|---|---|---|
| `from`, `to` | required | Opposite corners of an area, `[x, z]` scene pixels. |
| `height` | none | The grip's height over the area: a vial rises over a tall flask instead of sliding into it. |
| `blend` | `4` | Pixels round the area over which objects climb to `height`, or stay away from a closed area. |
| `closed` | `false` | Objects may not enter the area (a torch stays outside the burner it lights). |
| `nodes` | all | The grabbable node ids it applies to. |

Nothing in a scene is solid: without carry planes, a carried object passes through other objects at its lift height.

### slot

Shows the item of a station slot. It is clicked exactly like a vanilla container slot: left click picks up or puts
down the stack, right click splits or puts one, shift-click moves between the slot and the inventory, double click
gathers, dragging over several slots spreads.

| Field | Default | Meaning |
|---|---|---|
| `slot` | required | The station slot: `"grid[4]"`, or `"fuel"` for `fuel[0]`. |
| `display` | `"flat"` | `flat` lying on the surface, `upright` standing (facing the front), `hidden` not drawn (the scene shows what is in the slot its own way), `floating` hovering and turning. |
| `size` | `6` | Size of the slot (drop zone, highlight), 1..64; also of the item without `item_size`. |
| `item_size` | `size` | Size of the shown item when it differs. |
| `counts` | `"always"` | When the stack's count is drawn: `always`, `hover` (slots close together stay readable), `never`. |
| `highlight` | `true` | The square over the slot under the cursor. Off for things that are no slots to the eye, like food in a pot; the hover outline stays. |

A slot node needs no visual: it is an invisible pad on the surface. Put it where the block's texture shows a cell.

### output

Shows a result the station logic computes (`state().setOutput`); clicking it calls `StationLogic.takeOutput`.

| Field | Default | Meaning |
|---|---|---|
| `output` | required | The output's name. |
| `display` | `"floating"` | As for slots. |
| `size` | `8` | Size of the item and of its click zone. |

### control

The node moves with the value of a station control: a knob or toggle turns round `axis` from `range[0]` (the
control's minimum) to `range[1]` (its maximum) in degrees; a slider or button moves along `axis` by that many pixels.
Axis, range and pivot are in the node's own space, after its rotation and scale.

| Field | Default | Meaning |
|---|---|---|
| `control` | required | The station control, declared in the schema. |
| `type` | required | `button` (click; springs back), `toggle` (click flips 0/1), `knob` (turned), `slider` (dragged). |
| `axis` | by type | button `[0,-1,0]`, toggle `[1,0,0]`, knob `[0,1,0]`, slider `[1,0,0]`. |
| `range` | by type | button `[0,1]`, toggle `[-25,25]`, knob `[135,-135]`, slider `[0,8]`. |
| `circular` | `true` | A knob is grabbed anywhere and turned by circling the mouse round it. `false`: drag up or right. The mouse wheel also turns knobs. |
| `pivot` | `[0, 0, 0]` | The point a knob or toggle turns round, in the node's pixels: a lever's hinge. |
| `base` | none | A visual that stays still while the node moves (a lever's plate, a slider's track), drawn where the node rests. |
| `smoothing` | `0.05` | Seconds the node takes to cover half the way to a new value. `0.05` follows the hand; `0.2`-`0.5` glides, for a needle bound to a value the logic sets in steps. |

Stepped controls (`ControlSpec.stepped`) snap with a detent sound. Locked controls (`ControlSpec.locked()`) are shown
but only the logic changes them: a lit burner, a progress gauge. A node bound to a locked control is a display - no
outline, no clicks, the cursor passes through it - so a gauge needle is just a knob node on the locked value:

```json
{"id": "needle", "pos": [0, 7, -0.2], "visual": {"model": "mymod:diegesis/needle"},
 "control": {"control": "heat", "type": "knob", "axis": [0, 0, 1], "range": [-90, 90], "circular": false, "smoothing": 0.25}}
```

### Ready controls

| Template | Type | Notes |
|---|---|---|
| `diegesis:control/lever` | toggle | Hinge plate + lever, turns round its hinge. |
| `diegesis:control/switch` | toggle | Rocker switch in a bezel, +-12 degrees. |
| `diegesis:control/button` | button | Steel bezel with a red cap, travels 0.8 pixels. |
| `diegesis:control/knob` | knob | Red knob on a dial with tick marks; minimum on the screen's left. |
| `diegesis:control/slider` | slider | Grip on steel rails; the value grows to the screen's right, +-4 pixels. |

Each is a full 16-pixel model; scale it down to fit: `"scale": 0.5` is a typical table-top control. They need only an
`id`, a `pos` and the control: `{"id": "gas", "template": "diegesis:control/knob", "pos": [12, 16, 4], "scale": 0.55,
"control": {"control": "gas"}}`. `"tint"` inside `visual` recolours one.

### tank

Shows a station tank as liquid inside the node. It fills `inner` from the bottom as a real volume: tipping the vessel
moves the liquid but never changes how much there is. The surface rocks and carries ripples when the vessel is moved or
something falls in; a narrow tube chatters, a wide bowl swings slowly.

With a `spout`, a grabbable node pours. The player points it at another tank that takes the liquid - the cursor
anywhere on that tank or just above its opening. The vessel waits beside the tank, then tips until the liquid reaches
the spout, so a full vessel pours early and an emptying one goes on tipping. How far the liquid stands over the spout
is the flow; the **mouse wheel** asks for more or less. A stream runs into the other tank, raising ripples where it
lands. When the tank fills or the vessel runs dry, the vessel rights itself in place. Nothing is pulled in from afar: the
zone is the tank itself plus `pour_range`. The server paces pouring at the tanks' `pourRate`.

| Field | Default | Meaning |
|---|---|---|
| `tank` | required | The station tank. |
| `inner` | required | The box the liquid fills, node pixels. |
| `round` | `false` | A cylinder standing in `inner` instead of a box. |
| `spout` | none | Where liquid leaves, node pixels. Without one the node only receives. |
| `pour_range` | `2.5` | How far (pixels) round another tank the cursor still counts as pointing at it, 0.5..64. |
| `pour_angle` | `100` | How far the node tips at most, 0..135; it stops earlier once the liquid reaches the spout. Above 90 a bottle with a neck empties completely. |
| `opening` | top of `inner` | Where poured liquid goes in: the top of a flask's neck. |
| `pour_height` | `3` | How high over the other tank's opening the spout is held, 0..32: the stream is in plain view. |

### emitter

| Field | Default | Meaning |
|---|---|---|
| `particle` | required | A style: `diegesis:spark`, `bubble`, `bubble_pop`, `droplet`, `splash`, `steam`, `smoke`, `glint`, `dust`, `flame`, or your own (`DiegesisClient.registerParticle`). |
| `rate` | `4` | Particles per second, 0..200 (`NodeView.setEmitRate` changes it). |
| `offset` | `[0, 0, 0]` | Where they start, node pixels. |
| `velocity` | `[0, 0, 0]` | Start velocity, pixels per second; turns with the node. |
| `spread` | `0.5` | Random spread of the start, pixels. |
| `color` | style's | ARGB tint. |
| `enabled` | `true` | Emits from the start (`NodeView.setEmitting`). |
| `scale` | `1` | Size factor over the style's size. |
| `in_liquid` | `false` | Start anywhere in the node's liquid and die at its surface: bubbles. |

The built-in styles' sizes, in pixels at `scale` 1, so you can pick a `scale` for your props (a flame under a burner
drawn at scale 0.5 reads at `scale` 3-4):

| Style | Size (px) | Life (s) | Moves |
|---|---|---|---|
| `spark` | 0.5-0.8 | 0.25-0.6 | flies and falls, stretched |
| `bubble` | 0.55-0.95 | 0.6-1.4 | rises, wobbles, dims with its depth; on the surface it becomes `bubble_pop` |
| `bubble_pop` | 0.8-1.3, swells | 0.45-0.85 | sits on the surface, pops and rings the liquid |
| `droplet` | 0.3-0.45 | 0.5-0.9 | falls fast, stretched |
| `splash` | 0.4-0.7 | 0.25-0.45 | falls |
| `steam` | 0.9-1.5, grows | 1.1-1.9 | white, appears softly, rises slowly |
| `smoke` | 0.8-1.3, grows | 1.5-2.5 | rises slowly |
| `glint` | 0.4-0.7 | 0.3-0.7 | stays, glows |
| `dust` | 0.15-0.3 | 1.0-2.0 | drifts down |
| `flame` | 0.35-0.6 | 0.25-0.5 | rises, glows |

### light

| Field | Default | Meaning |
|---|---|---|
| `level` | `14` | Brightness like a block's light, 0..15 (`NodeView.setLight`). |
| `radius` | `16` | How far it reaches, pixels. |
| `color` | `"#80FFD9A0"` | Colour of the soft light pool on the work surface; alpha is its strength, 0 draws none. |
| `flicker` | `0.12` | 0 for a steady lamp, up to 1. |
| `offset` | `[0, 0, 0]` | Where the light comes from, in the node's pixels: the middle of a long lamp whose model is anchored at its corner. |

The light brightens nodes near it (also while it is carried) and lights the surface under it. It does not light the
world: it is a scene effect.

## inventory

Without `inventory`, a station with slots or outputs shows the vanilla inventory panel at the bottom of the screen
(`interface.inventory_panel`). With it, the player's 36 slots lie in the scene: 4 rows of 9 in vanilla order - the
hotbar is the row nearest the player, slots 9-17 the farthest, column 0 on the player's left. They work like the
panel: clicks, shift-clicks, drags, tooltips, counts.

| Field | Default | Meaning |
|---|---|---|
| `pos` | required | Centre of the grid, scene pixels. |
| `rotation` | `[0, 0, 0]` | Turn of the grid, degrees; flat by default, `[-18, 0, 0]` tilts it towards a camera in front. |
| `spacing` | `2.6` | Pixels between slot centres. |
| `size` | `2.1` | Size of the items. |
| `surface` | none | A block state drawn as a board under the slots; floating slots without one. |
| `slot_color` | `"#66000000"` | Colour of each slot's square; alpha 0 hides them. |
| `show` | `"always"` | `toggle`: hidden until Tab (Tab hides it again), shown by itself while an item is on the cursor. |

## inventory_drawer

Keeps the inventory off the scene until it is wanted. An icon sits at a side of the screen; pointing at that side
slides the inventory out **over** the scene, and it slides back when the cursor leaves. A click on the icon or Tab pins it
open (Escape or Tab lets it go). While it is out it comes first: whatever lies under it in the scene is neither hovered
nor clicked, and the wheel does nothing there.

A stack on the cursor opens it like the cursor alone does, so an item taken from the scene can be put away; an object
in the hand or a control being turned does not, so that dragging a vessel to the edge opens nothing. Stacks can be
dragged straight out of it and let go over a slot in the scene.

```json
"inventory_drawer": {"side": "right", "style": "minimal"}
```

| Field | Default | Meaning |
|---|---|---|
| `side` | `"right"` | `left`, `right`, `top`, `bottom`. |
| `align` | `0.5` | Where along that side it sits: 0 at the start (left or top), 1 at the end. |
| `icon` | `"minecraft:bundle"` | The item on the icon. |
| `icon_texture` | none | A 16x16 texture on the icon instead, such as `"mymod:textures/gui/bag.png"`. |
| `hover_zone` | `10` | How wide (GUI pixels) the strip along the side is that opens it; 0: only the icon does. |
| `open_delay` | `0.08` | Seconds the cursor must stay there: passing by opens nothing. |
| `close_delay` | `0.35` | Seconds it waits after the cursor left: overshooting closes nothing. |
| `slide_time` | `0.18` | Seconds it takes to slide. |
| `margin` | `4` | Gap (GUI pixels) between the open drawer and the side of the screen. |
| `style` | `"panel"` | `panel`: the vanilla inventory panel. `minimal`: dark see-through squares. |
| `background` | `"#C0101216"` | Background of the `minimal` style. |
| `slot_color` | `"#66000000"` | Colour of a slot in the `minimal` style. |
| `always_open` | `false` | It slides out when the station opens and stays out, without an icon; Escape leaves the station at once. For stations worked from the inventory all the time. |
| `compact` | `false` | Only the stacks the station can use, in rows of up to nine, the drawer no bigger than they are; the `minimal` look. Client code picks them with `StationView.setInventoryFilter`, without one every stack that is not empty shows. A stack on the cursor gets one empty slot to go into. It changes shape only while the cursor is away from it. |

Client code: `StationView.setInventoryOpen(boolean)`, `isInventoryOpen()`, `setInventoryFilter(Predicate<ItemStack>)`, `SceneEvent.InventoryDrawer`.

## pouring

How pouring looks; every field is optional.

| Field | Default | Meaning |
|---|---|---|
| `hints` | `true` | While a vessel with liquid is in the hand, tanks that would take it are marked at their openings. |
| `hint_color` | `"#40FFFFFF"` | Colour of those marks. |
| `meter` | `true` | A small bar next to the cursor shows how full the tank being poured into is. |
| `stream` | `true` | The liquid runs in a continuous stream; `false` leaves only drops. |
| `stream_width` | `1.4` | How wide (pixels) the stream leaves the spout at full flow. |

## interface

Every field is optional.

| Field | Default | Meaning |
|---|---|---|
| `hide_hud` | `true` | Hide the HUD (hotbar, health, crosshair, effects). |
| `hide_chat` | `true` | Hide the chat. |
| `hide_player` | `false` | Do not draw the local player's own body (others still see it). |
| `inventory_panel` | `true` | Without an `inventory` layout, show the vanilla panel; `false` shows no inventory. |
| `show_counts` | `true` | Write stack counts next to items in the scene. |
| `counts_on_top` | `true` | Counts are never hidden by a model. |
| `highlight_color` | `"#4DFFFFFF"` | Colour over the slot under the cursor. |
| `shadows` | `true` | Carried things cast a shadow of their shape on the work surface. |
| `shadow_opacity` | `0.5` | How dark, 0..1. |
| `carry_lift` | `2.5` | How high the held stack floats over the work surface. |
| `carry_stick` | `false` | The held stack settles onto the slot under the cursor. |
| `carry_sway` | `1` | How much the held stack leans, 0..4. |
| `carry_margin` | none | How far beyond the block's sides carried things may go; no limit when absent. |
| `sounds` | `true` | The library's quiet sounds for picking up, putting down and controls. |
| `hover_outline` | `true` | Outline the usable object under the cursor while the hand is empty. |
| `outline_color` | `"#FFE6E6E6"` | Colour of that outline. |

## clear_view

While a player works at the station, entities and the world's particles between the camera and the station dissolve: softly, towards
the edge of a cone round the line of sight, and they come back as the camera leaves. A figure in the way stays as a faint ghost at
the edge of the shot and never covers the scene; a campfire's smoke under a pot stops at the cone. Nobody else sees a change, and
the scene's own particles stay. On by default; every field is optional.

| Field | Default | Meaning |
|---|---|---|
| `entities` | `true` | Entities dissolve. |
| `particles` | `true` | World particles dissolve. |
| `radius` | `16` | How wide the clear cone is at the station, pixels. |
| `soft` | `12` | How far beyond `radius` things come back to full strength, pixels. |

## Meshes

A visual can be any polygon model, not only Blockbench cubes: export OBJ from Blender (or any 3D tool), put it at
`assets/<namespace>/diegesis/meshes/<path>.obj` with one unit per pixel, and name it with a texture of the block atlas:

```json
"visual": {"mesh": "mymod:chess/king", "texture": "mymod:block/chess/white_king"}
```

- `v`, `vt`, `vn` and `f` are read (triangles, quads, larger polygons); materials and groups are ignored, one mesh has one
  texture. Normals are used as they are, so smooth shading comes through; without `vn` faces are flat.
- A texture under `textures/block/` is in the block atlas by itself. UVs stay inside the sprite: no tiling.
- The default `anchor` is the file's own origin. Keep the model's base at y 0 and the object stands on the node's `pos`.
- The same file can take any texture: white and black chess pieces are one mesh each, two sprites.
- Tint, `translucent`, `emissive`, `glass`, `double_sided`, shadows and the hover outline work as on block models.
- Keep them small: a mesh is drawn every frame, 100 to 300 faces per object is plenty.
- Objects close together, seen at an angle: `"precise": true` on the node hits the mesh's own polygons, so a box round one
  object does not catch a click meant for the one behind it. The `collider` stays the first, rough test.

## Repeating a node

`repeat` lays a node out on a grid: the 64 squares of a board are one entry. Each cell is the node `step` further on, with
the id `<id>_0`, `<id>_1`... (x counts fastest, then y, then z; child nodes get the same ending):

```json
{"id": "mark", "pos": [26.5, 14.7, 5.5], "repeat": {"count": [8, 1, 8], "step": [-3, 0, 3]}, "pickable": false, "hidden": true}
```

## Views from seats

A station with seats (see [Stations](stations.md#seats)) gives each its own view. Whoever holds no seat, or a seat without
a shot, sees `position` and `target`:

```json
"camera": {
  "position": [54, 44, 16], "target": [16, 14, 16],
  "seats": {
    "white": {"position": [16, 47, -13], "target": [16, 14, 13.5], "fov": 40},
    "black": {"position": [16, 47, 45], "target": [16, 14, 18.5], "fov": 40}
  }
}
```

## placing

Free placing: the player puts items down anywhere on the scene's surfaces, turned and sized as they like, and they stay there
for everyone to see. It shows a slot group with `placed()` rules; every item of the group is an object of the scene, so the
file has no node per item.

```json
"placing": {
  "group": "items",
  "item_size": 6,
  "surfaces": [
    {"from": [2, 2.5], "to": [30, 14.5], "height": 12, "clearance": 8, "aim_height": 7, "wall": 15}
  ]
}
```

| Field | Default | Meaning |
|---|---|---|
| `group` | required | The slot group that holds the items. |
| `surfaces` | required | Where they can stand, see below. |
| `item_size` | 6 | Pixels the longest side of an item measures at size 1. |
| `turn_step` | 15 | Degrees of one notch of the wheel. |
| `scale_range` | [0.5, 1.5] | The sizes a player can give an item, as factors of `item_size`. |
| `scale_step` | 0.1 | What one notch of the wheel changes the size by. |
| `turn_speed` | 1.2 | Degrees an item turns per GUI pixel of a drag. |
| `stacking` | true | Items can stand on each other; what stood on an item comes down when it is taken away. |
| `block_models` | true | A block item with a flat icon (a lantern, a torch, a flower pot) stands there as the block. |
| `item_light` | true | Items of blocks that give light shine on the scene, candles and torches burn. |
| `light_radius` | 11 | How far their light reaches, in pixels. |
| `light_color` | `#C0FFC888` | Colour of the pool of light they throw; alpha is its strength. |
| `shadows` | true | Items cast their shape on what they stand on. |
| `snap` | 0.25 | Positions are rounded to this many pixels; 0 keeps them as they are. |
| `preview_color` | `#A0FFFFFF` | Colour of the see-through copy that shows where the item in the hand would stand. |

A surface:

| Field | Default | Meaning |
|---|---|---|
| `from`, `to` | required | Two corners, `[x, z]`. |
| `height` | required | Its height. |
| `clearance` | 64 | Room above it: an item that would not fit is made smaller. |
| `aim_height` | 0 | How the cursor finds a place. 0: it points at the surface itself, right for a table seen from above. A shelf seen from the front is too thin a sliver for that: with a height, the cursor points at a slope from the front edge (the side of the smaller z) up to this height over the back edge, so that low in the opening means at the front and high in the opening means at the back. |
| `wall` | none | The z of a wall behind the surface: light in the room falls on it too. |

What the player does: a stack on the cursor stands see-through where the cursor points. A click puts it down (as much of it as
a slot takes: with `maxStack(1)` one item, and the rest stays in the hand for the next place). A click on an item takes it,
with the turn and size it had; a drag moves it. The wheel turns what is in the hand or under the cursor, Shift+wheel tips it
towards the player, Control+wheel sizes it; the right button held turns it freely with the mouse. Items settle on their lowest
point whichever way they are turned. Shift-click takes an item back into the inventory.

The light of lights in the scene stays in the room it is in: a pool on the surface under it and on its `wall`, cut off at the
surface's edges, not through a board to the shelf below.

## captions

How the lines of `StationView.showCaption` look: `edge` (`top` or `bottom`), `color` (`#FFFFFFFF`), `background`
(`#80000000`; a zero alpha draws no band).

## Node templates

A node template is a node without an `id`, in `assets/<namespace>/diegesis/nodes/<path>.json`
([`schema/node.schema.json`](schema/node.schema.json)). A scene node with `"template": "<namespace>:<path>"` starts
from it and lays its own fields over it:

- objects merge field by field (`"control": {"control": "gas"}` keeps the template's control type, axis and base);
- other values replace the template's - also lists: a node's own `children` replace the template's;
- `null` removes a field of the template (`"base": null`);
- a template may start from another template; a loop is an error.

```json
{
  "visual": {"model": "mymod:diegesis/valve"},
  "control": {"type": "knob", "range": [0, -270], "base": {"model": "mymod:diegesis/valve_base"}},
  "scale": 0.5
}
```

## Debugging a scene

- **F3+B**, `/diegesis debug` or `DiegesisClient.setDebugView(true)`: hit boxes coloured by role with node ids, the
  laid-out inventory slots, the work plane, the camera target.
- **F3+T** reloads every scene and template. Watch the log for `Scene <id> (<file>) is broken: <reason>`.
- A node you cannot click is usually covered by a bigger collider in front of it (the debug view shows which).
- **Flicker**: faces that lie on each other fight for the same pixels, and a model that is clean on its own can do it in a
  scene. Check before you look: `python tools/scenes/depth_check.py` (needs numpy) puts the block's model, every node's
  model and every tank's walls in one space and lists the faces that touch. Point it at your mod with `--assets
  src/main/resources/assets`, and name the block's models in `tools/scenes/blocks.json`, a scene does not know them.

Solid parts must stay 0.05 px apart, measured in the scene, after the node's `scale`: closer ones flicker from a distance
of the station's drawing range. See-through parts need no gap from solid ones: glass may stand on the table and a liquid may
fill a sink to its walls, the see-through layer always wins. Two see-through faces in one plane are a problem again, as
their order then depends on the camera.
