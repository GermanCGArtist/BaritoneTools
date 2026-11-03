# BaritoneTools

Addons for [Baritone](https://github.com/cabaletta/baritone), written for **Minecraft 26.1.1 on Fabric**. They work together but each one runs on its own.

| Mod | What it does |
|---|---|
| [`mc-26.1.1/loadchest`](mc-26.1.1/loadchest) | **LoadChest** – walks to a chest and empties your inventory into it (keep rules, hotbar toggle). Can do it automatically when the inventory is full, and collect drops (only the ones Baritone mined, or everything). |
| [`mc-26.1.1/survivalkit`](mc-26.1.1/survivalkit) | **SurvivalKit** – eats (stays at full health), fights hostile mobs, sleeps through the night and restocks tools/food from a supply chest. Works with Baritone's item saver. |
| [`mc-26.1.1/buildtools`](mc-26.1.1/buildtools) | **BuildTools** – safe areas Baritone never breaks into (with per-area mining/bridging rules), water drain/fill, water escape. |
| [`mc-26.1.1/queue`](mc-26.1.1/queue) | **Baritone Queue** – a script of Baritone commands in a code-style editor (colours, completion, Test, presets, repeat/if/wait until, progress bar). |
| [`mc-26.1.1/baritoneui`](mc-26.1.1/baritoneui) | **BaritoneUI** – an in-game screen (key **B**, rebindable) to toggle Baritone settings and the two addons above. |

All of them hook into Baritone as temporary processes, so a running `#mine` / `#farm` / `#build` / `#sel ca` is paused and then continues by itself.

## Installing (players)
Put these in your `mods` folder:
- Fabric API for 26.1.1 (tested with 0.145.4)
- **`baritone-api-fabric-1.18.0.jar`** from the [Baritone v1.18.0 release](https://github.com/cabaletta/baritone/releases/tag/v1.18.0) (Baritone for Minecraft 26.1). Use the `api` jar, not `standalone`: addons cannot talk to the standalone jar.
- the addon jars you want (`loadchest`, `survivalkit`, `buildtools`, `queue`, `baritoneui`)

## Building
1. Install JDK 25, or unpack a portable one to `tools/` in the repo root.
2. Download `baritone-api-fabric-1.18.0.jar` into the `libs/` folder of the mod you build.
3. In the mod's folder run `build.bat` (Windows) or `./gradlew build`. The jar ends up in `build/libs/`.

Each mod folder has its own README with all commands and settings.

## Older code
The branch `loadchest-addon` has the first LoadChest version for Minecraft 1.21.1.
