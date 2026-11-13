# Diegesis documentation

Diegesis turns a block's GUI into an interface inside the world. A mod describes it in two parts:

- a **station** (Java, common code): which blocks open it, what state it keeps (slots, tanks, controls, outputs), what
  rules it follows;
- a **scene** (a JSON resource): where the camera goes, what is drawn where, what the player can carry, click and turn.

| Page | |
|---|---|
| [Getting started](getting-started.md) | Dependency, a first station and scene, trying it in game. |
| [Scenes](scenes.md) | Every field of the scene file, node templates, ready controls, debugging. |
| [Stations](stations.md) | The station builder, schema, logic, keeping state in a block entity, multiblocks. |
| [Client](client.md) | Behaviours, node animation, scene events, effects, particles, liquids, sounds. |
| [Cookbook](cookbook.md) | A board game, crafting on a block, a lever and a lamp, a gauge, pouring, a torch and a burner. |
| [`schema/scene.schema.json`](schema/scene.schema.json) | JSON Schema of scene files (and [`node.schema.json`](schema/node.schema.json) for node templates). |
