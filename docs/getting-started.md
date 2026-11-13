# Getting started

This page takes you from nothing to a working station: a block whose use flies the camera to it, with a knob the
player turns and a slot they drop an item into.

## 1. Depend on Diegesis

Diegesis is a library mod for Minecraft 1.21.1 on NeoForge and Fabric. Players install it next to your mod (client, and
server for server stations). Add it to your build:

```groovy
repositories {
    maven { url = "https://cursemaven.com"; content { includeGroup "curse.maven" } }
    maven { url = "https://api.modrinth.com/maven"; content { includeGroup "maven.modrinth" } }
}

dependencies {
    // NeoForge, ModDevGradle (a mod on the classpath is loaded in dev runs)
    implementation "curse.maven:diegesis-<project-id>:<neoforge-file-id>"
    // Fabric, Loom
    modImplementation "maven.modrinth:diegesis:<fabric-version>"
}
```

`<project-id>` and the file ids are on the mod's CurseForge page (the file's URL ends with its id). Declare the
dependency in `neoforge.mods.toml` (`modId="diegesis"`, `type="required"`) or `fabric.mod.json`
(`"depends": {"diegesis": "*"}`).

Everything you use is in `com.pockyl.diegesis.api`. The rest (`impl`, `platform`, `mixin`) is internal and may change
in any version.

## 2. Declare the station (common code)

A **station** says which blocks open an interface and what state it keeps. Create it in code that runs on both sides,
during mod setup (NeoForge: your mod constructor; Fabric: `onInitialize`).

```java
public final class MyStations {
    public static final StationType STILL = Diegesis.station(ResourceLocation.fromNamespaceAndPath("mymod", "still"))
            .block(MyBlocks.STILL)                       // a DeferredBlock / any Supplier<Block>, looked up late
            .schema(StationSchema.builder()
                    .slots("input", 1)                   // one slot: "input[0]", or "input" for short
                    .control("heat", ControlSpec.range(0, 1, 0))
                    .build())
            .logic(StillLogic::new)                      // optional server rules
            .build();

    public static void init() {
        Diegesis.register(STILL);
    }
}
```

The server checks every player action against the schema: an item cannot go into a slot that does not exist, a knob
cannot turn past its range. Items in slots go back to the player when the last player leaves (like the vanilla
crafting table) unless the slot group is `kept()` - see [Stations](stations.md).

## 3. Write the scene (resources)

The **scene** is what the player sees and touches. It is a JSON file named like the station:
`assets/mymod/diegesis/scenes/still.json`.

```json
{
  "$schema": "https://raw.githubusercontent.com/Pocky-l/diegesis/main/docs/schema/scene.schema.json",
  "camera": {"position": [8, 34, -8], "target": [8, 16, 7], "fov": 55},
  "nodes": [
    {"id": "input", "pos": [5, 16, 9], "slot": {"slot": "input", "size": 4}},
    {"id": "heat", "template": "diegesis:control/knob", "pos": [11, 16, 5], "scale": 0.6,
     "control": {"control": "heat"}}
  ]
}
```

- Coordinates are **Blockbench pixels** relative to the block's corner (16 = one block), for a block facing **north**
  (its front on the -Z side, the way Blockbench shows a block). The library turns the scene with the block.
- `diegesis:control/knob` is a ready control with its model; there are also `lever`, `switch`, `button` and `slider`.
- The `$schema` line gives your editor completion and checks; the game ignores keys that start with `$`.

## 4. Optional: rules and looks

Server rules go into a `StationLogic`; every method has a harmless default, so implement only what you need:

```java
final class StillLogic implements StationLogic {
    StillLogic(StationContext station) {
    }

    @Override
    public void onControlChanged(StationContext station, Player player, String control, float value) {
        if (value > 0.9F) {
            station.effect(DiegesisParticles.STEAM, "input");   // everyone around sees the steam
        }
    }
}
```

Client looks (animations, sounds) go into a `StationBehavior`, registered from client setup only:

```java
DiegesisClient.registerBehavior(MyStations.STILL.id(), StillBehavior::new);
```

## 5. Try it

- Use the block: the camera flies in, the inventory panel shows under it, drag an item onto the slot, turn the knob.
  Escape or the inventory key (E) leaves; so does walking out of reach.
- **F3+T** reloads scenes while the game runs. A broken scene is not used, and the log says why
  (`Scene mymod:still (...) is broken: Unknown field "pso" (did you mean "pos"?)`).
- **F3+B** (or `/diegesis debug`) shows every hit box coloured by role with its node id, the inventory slots, the work
  plane and the camera target.

Next: [Scenes](scenes.md) for every field of the scene file, [Stations](stations.md) for the Java side,
[Client](client.md) for behaviours, particles, sounds and liquids, [Cookbook](cookbook.md) for common interfaces.
