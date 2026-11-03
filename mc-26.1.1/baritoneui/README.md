# BaritoneUI (Fabric 26.1.1)

An in-game screen for [Baritone](https://github.com/cabaletta/baritone): toggle settings with the mouse instead of typing `#set ...`. Also controls the **LoadChest**, **SurvivalKit** and **BuildTools** addons when they are installed.

Open it with **B** (change it in *Options > Controls > Baritone UI*). Press the same key again, or Esc, to close it.

## Tabs
| Tab | What it does |
|---|---|
| **Common** | The settings you flip most (allowBreak, allowPlace, allowParkour, allowSprint, itemSaver + threshold, mineScanDroppedItems, legitMine, buildInLayers, replantCrops, chatControl, ...) and a **Stop** button (`#stop`). |
| **All settings** | Every true/false and number setting Baritone has, in alphabetical order. |
| **LoadChest** | Item collection mode (**Off / Only items I mined / All items**), auto-empty, hotbar deposit, set/use the deposit chest, keep rules (edit, remove, add). |
| **SurvivalKit** | Eat, golden apple, defence, sleep, restock, keep running when unfocused, eat threshold; set the bed and supply chest, sleep/restock now, the want-list (edit, remove, add). |
| **BuildTools** | Auto-remove water that pauses the builder, the dam option, *Drain now*, *Fill selection* with water, *Stop*, and the **safe areas** (switch, add from selection / saved chests / around you, remove). |

- The **search box** filters the current tab by name.
- **Mouse wheel** scrolls the list.
- ON/OFF buttons switch immediately. Number fields apply as you type (red = not a valid number).
- Changes to Baritone settings are saved to Baritone's own settings file, exactly like `#set`.
- A singleplayer world is **not** paused while the screen is open, so a running task keeps going.
- **Set chest / Set bed** buttons act on the block your crosshair pointed at when you opened the screen.

## How it talks to the other mods
BaritoneUI has no hard dependency on LoadChest or SurvivalKit. It switches their settings by running their chat commands (`/loadchest ...`, `/survival ...`) and shows their state by reading their config files, so the tabs simply say "not installed" if a mod is missing.

## Build
Run `build.bat` (portable JDK 25 from `../../tools`) -> `build/libs/baritoneui-<version>.jar`.
Put it in `mods/` with Fabric API and **`baritone-api-fabric-1.18.0.jar`** (not the standalone jar).

## 1.9.0
SurvivalKit tab: Work items editor (colours, Tab completion, Test), extra supply chests, thunderstorm bed, auto-reconnect, session report.
