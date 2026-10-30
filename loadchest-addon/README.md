# LoadChest – Baritone addon (Fabric 1.21.1)

Adds a client command that makes Baritone walk to the nearest chest and fill it with your items.

## Choosing the chest
- `/loadchest set` – save the chest you are looking at
- `/loadchest set <x> <y> <z>` – save a chest by coordinates
- `/loadchest at <x> <y> <z> [filter]` – one-off, use this chest without saving
- `/loadchest nearest [filter]` – ignore the saved chest, use the nearest one
- `/loadchest clear` – forget the saved chest

## Loading
- `/loadchest` – deposit everything from the main inventory (hotbar is kept) into the saved chest (nearest if none saved)
- `/loadchest all` – deposit everything including the hotbar
- `/loadchest <text>` – only items whose id contains the text (e.g. `cobblestone`, `diamond`)
- `/loadchest stop` – abort

The saved chest is kept until you quit the game.

## Build
1. Download `baritone-api-fabric-<version>.jar` matching your Minecraft version from the Baritone releases and put it in `libs/`.
2. Adjust the versions in `gradle.properties` and the jar name in `build.gradle`.
3. `gradle build` -> jar in `build/libs/`.
4. Put the jar in `mods/` together with Fabric API and the Baritone Fabric jar.

## Notes
- Searches chests within 48 blocks; opens the chest, then shift-clicks one slot every 2 ticks.
- Stops when your inventory is done or the chest is full for the remaining items.
- Mappings are Yarn names for 1.21.1; other versions may need small renames.
