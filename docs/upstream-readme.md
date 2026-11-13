# Original project documentation

This archived README describes the original 1.21.1 release. Consult this repository’s main README for the 26.3 Fabric port.

<p align="center">
  <img src="common/src/main/resources/logo.png" alt="Diegesis" width="160">
</p>

<h1 align="center">Diegesis</h1>

<p align="center">
  <b>A library for block interfaces inside the world.</b> Using a block moves the camera to it, and the player works with
  its 3D parts directly, with no menu screen.
</p>

<p align="center">
  <a href="https://www.curseforge.com/minecraft/mc-mods/diegesis"><img alt="CurseForge downloads" src="https://img.shields.io/curseforge/dt/1790227?logo=curseforge&label=CurseForge&color=F16436"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/diegesis/files"><img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A"></a>
  <a href="https://neoforged.net"><img alt="NeoForge" src="https://img.shields.io/badge/Loader-NeoForge-D7742F"></a>
  <a href="https://fabricmc.net"><img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-C6BBA5"></a>
  <a href="docs/README.md"><img alt="Documentation" src="https://img.shields.io/badge/Docs-guide%20%26%20cookbook-4A6CB0"></a>
  <a href="LICENSE"><img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue"></a>
</p>

<p align="center">
  <img src="docs/showcase/soup.gif" alt="The cooking pot of Hands-On Delight, built with Diegesis" width="640">
  <br>
  <i>The cooking pot of <a href="https://www.curseforge.com/minecraft/mc-mods/hands-on-delight">Hands-On Delight</a>, built with Diegesis.</i>
</p>

Diegesis adds nothing to the game by itself. Other mods use it to build block interfaces without menu screens. A
mod describes a **station** in Java (which blocks open it, what state it keeps, what the rules are) and a **scene** in
JSON (what is drawn where and what can be touched). Diegesis moves the camera to the block, handles the clicks and
dragging, keeps the state in sync with the server and shows it to other players.

## Built with Diegesis

- 🍲 **[Hands-On Delight](https://www.curseforge.com/minecraft/mc-mods/hands-on-delight)** - the cooking pot, cutting
  board and skillet of [Farmer's Delight](https://www.curseforge.com/minecraft/mc-mods/farmers-delight) open right in
  the world ([source](https://github.com/Pocky-l/handson-delight)).

Made something with Diegesis? Open an issue and it will be listed here.

## Features

- 🎥 **Camera** - using a station block moves the camera to a point set in the scene. Other players see you look at the
  block.
- 🧱 **Scenes from Blockbench** - nodes show [Blockbench](https://www.blockbench.net) models, items or blocks. They can
  be nested, moved, turned, scaled, faded and made to glow.
- 📦 **Item slots** - clicks work as in a vanilla menu, including shift-click, double click and dragging a stack across
  slots. The inventory stays at the bottom of the screen, slides in as a drawer or is laid out in the scene.
- ✋ **Carried objects** - the player picks objects up and moves them over the block. They lag slightly behind the
  cursor, lean and cast a shadow.
- 🎛️ **Controls** - toggles, buttons, knobs and sliders. A lever, a switch, a button, a knob and a slider come ready to
  use with one line in a scene.
- 💧 **Liquids and pouring** - tanks hold real amounts of liquid that sway and ripple. Point a vessel at a tank to pour
  into it. The server checks every pour.
- ✨ **Particles, light and sound** - sparks, bubbles, steam, smoke and more, glowing parts that light up what is near
  them, and quiet station sounds a resource pack can replace.
- 🎯 **Triggers** - a carried object reports when it touches another part of the scene, and zones react to the cursor.
- 🪑 **Multiblocks and seats** - a station can cover several blocks and seat several players, each with their own view.
- 🔌 **Blocks from other mods** - a station can sit on another mod's block and use that block's inventory, so hoppers
  keep working.
- 🌐 **Client-only or server** - client stations work on any server. Server stations check every action and share one
  state between all players.
- 🔄 **Hot reload** - F3+T reloads scene files while the game runs, and a mistyped field is reported with its name.
- 🐞 **Debug view** - F3+B or `/diegesis debug` shows every hit box, node id and slot.
- 🌈 **Shaders** - scenes are drawn like entities and item frames, so shader packs handle them the same way.
- 📚 **Documentation** - a guide, a cookbook and a JSON schema for scene files.

## Quick start

Common code (both sides):

```java
public static final StationType LAB_TABLE = Diegesis.station(new ResourceLocation("mymod", "lab_table"))
        .blocks(MyBlocks.LAB_TABLE.get())
        .schema(StationSchema.builder()
                .slots("rack", 3)
                .control("gas", ControlSpec.range(0, 1, 0))
                .build())
        .logic(LabTableLogic::new)      // optional server rules: outputs, buttons, custom actions
        .register();
```

Scene, `assets/mymod/diegesis/scenes/lab_table.json` (Blockbench pixels, block facing north):

```json
{
  "camera": {"position": [8, 32, -6], "target": [8, 16, 8], "fov": 55},
  "nodes": [
    {"id": "flask", "pos": [8, 16, 9], "visual": {"model": "mymod:diegesis/flask", "translucent": true},
     "grabbable": {"lift": 3}},
    {"id": "rack_0", "pos": [3, 16, 12], "slot": {"slot": "rack[0]", "display": "upright"}},
    {"id": "gas_knob", "template": "diegesis:control/knob", "pos": [13, 16, 3], "scale": 0.6,
     "control": {"control": "gas"}}
  ]
}
```

Ready controls: `diegesis:control/lever`, `switch`, `button`, `knob` and `slider`. A node template is a node file
without an `id` in `assets/<namespace>/diegesis/nodes/`; the scene's own fields are laid over it (objects merge field
by field, `null` removes one). Your own control looks the same way: a moving `visual`, and `"base"` inside `control`
for the part that stays still, with `"pivot"` for the point a lever or knob turns round.

Interface options (all optional, defaults shown):

```json
"interface": {
  "hide_hud": true, "hide_chat": true, "hide_player": false,
  "inventory_panel": true, "show_counts": true, "counts_on_top": true, "highlight_color": "#4DFFFFFF",
  "shadows": true, "shadow_opacity": 0.5,
  "carry_lift": 2.5, "carry_stick": false, "carry_sway": 1.0, "sounds": true
}
```

Add `"carry_margin": 6` to keep carried things within 6 pixels of the block's sides (no limit by default).

The inventory can live in the scene instead of the screen panel:
`"inventory": {"pos": [8, 15.2, -5.4], "rotation": [-18, 0, 0], "spacing": 2.2, "size": 1.75, "slot_color": "#66000000"}`
(add `"surface": "<block state>"` for a board under the slots).

Client code (optional): `DiegesisClient.registerBehavior(id, MyBehavior::new)` for animations, sounds and effects that
react to the state and to what the player does.

## Documentation

| Page | What is in it |
|---|---|
| [Getting started](docs/getting-started.md) | The dependency, a first station and scene, trying it in game. |
| [Scenes](docs/scenes.md) | Every field of a scene file, node templates, ready controls, debugging. |
| [Stations](docs/stations.md) | The station builder, schema, logic, state in a block entity, multiblocks. |
| [Client](docs/client.md) | Behaviours, node animation, scene events, effects, particles, liquids, sounds. |
| [Cookbook](docs/cookbook.md) | A board game, crafting on a block, a lever and a lamp, a gauge, pouring, a burner. |
| [JSON Schema](docs/schema/scene.schema.json) | Add `"$schema": "https://raw.githubusercontent.com/Pocky-l/diegesis/main/docs/schema/scene.schema.json"` to a scene for completion and checks in the editor. |

## Compatibility

[![Sodium](https://raw.githubusercontent.com/Pocky-l/diegesis/1.21.1/docs/compat/sodium.png)](https://modrinth.com/mod/sodium) ✅ **[Sodium](https://modrinth.com/mod/sodium)** - scenes, hidden blocks and particles draw as usual

[![Iris](https://raw.githubusercontent.com/Pocky-l/diegesis/1.21.1/docs/compat/iris.png)](https://modrinth.com/mod/iris) ✅ **[Iris](https://modrinth.com/mod/iris)** - checked without a shader pack, liquids and glass included

## Installation

1. Install [NeoForge](https://neoforged.net) or [Fabric](https://fabricmc.net) (with
   [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)) for Minecraft 1.21.1.
2. Put the jar into the `mods` folder of the client, and of the server for server stations.

## Building

```sh
./gradlew build                  # neoforge/build/libs and fabric/build/libs
./gradlew :neoforge:runGameTestServer
```

## Credits

- Author: **Pocky**.

<!-- more-mods:start -->
## More mods by Pocky

**🌦️ Weather & nature**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards"><img src="docs/more-mods/hoarfrost.png" width="48" alt="Hoarfrost: Snow & Blizzards"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards"><b>Hoarfrost: Snow & Blizzards</b></a> - seven kinds of snowfall, a living wind and soft snow on the land (<a href="https://github.com/Pocky-l/hoarfrost">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms"><img src="docs/more-mods/petrichor.png" width="48" alt="Petrichor: Rain & Storms"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms"><b>Petrichor: Rain & Storms</b></a> - realistic rain, puddles, drips and branching lightning (<a href="https://github.com/Pocky-l/petrichor">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><img src="docs/more-mods/rustling_leaves.png" width="48" alt="Rustling Leaves"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><b>Rustling Leaves</b></a> - falling leaves and leaf piles you can wade through and rake, client-side (<a href="https://github.com/Pocky-l/rustling-leaves">source</a>)</td>
  </tr>
</table>

**🎨 Graphics & performance**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><img src="docs/more-mods/dhturbo.png" width="48" alt="Turbo for Distant Horizons"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><b>Turbo for Distant Horizons</b></a> - Distant Horizons terrain generated several times faster (<a href="https://github.com/Pocky-l/dhturbo">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/shaders/pocky-shaders"><img src="docs/more-mods/pocky_shaders.png" width="48" alt="Pocky Shaders"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/shaders/pocky-shaders"><b>Pocky Shaders</b></a> - a cosy, natural shader pack that shows the weather of these mods (<a href="https://github.com/Pocky-l/pocky-shaders">source</a>)</td>
  </tr>
</table>

**🔮 Magic**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/hextale"><img src="docs/more-mods/hextale.png" width="48" alt="HexTale"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/hextale"><b>HexTale</b></a> - hextech magic to research, distil and bind with runes, inspired by Arcane and Thaumcraft (<a href="https://github.com/Pocky-l/HexTale">source</a>)</td>
  </tr>
</table>

**⚡ Tech & energy**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators"><img src="docs/more-mods/mekanismadvancedgenerators.png" width="48" alt="Mekanism Advanced Generators"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators"><b>Mekanism Advanced Generators</b></a> - nine tiers of solar panels, big solar generators, cubes and cables for Mekanism (<a href="https://github.com/Pocky-l/Mekanism-MoreSolarPanels">source</a>)</td>
  </tr>
</table>

**🛠️ Server tools**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/inventory-backups"><img src="docs/more-mods/inventorybackups.png" width="48" alt="Inventory Backups"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/inventory-backups"><b>Inventory Backups</b></a> - inventory and ender chest backups with one-click restore for admins (<a href="https://github.com/Pocky-l/inventory-backups">source</a>)</td>
  </tr>
</table>

**🧩 Libraries & addons**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/hands-on-delight"><img src="docs/more-mods/handson_delight.png" width="48" alt="Hands-On Delight"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/hands-on-delight"><b>Hands-On Delight</b></a> - cook in Farmer's Delight with your own hands, right on its blocks (<a href="https://github.com/Pocky-l/handson-delight">source</a>)</td>
  </tr>
</table>

<!-- more-mods:end -->

## License

[MIT](LICENSE)
