# LoadChest – Baritone addon (Fabric 1.21.1)

Adds a client command that makes Baritone walk to a chest and deposit items, with keep limits (e.g. keep 128 scaffolding dirt) and an optional "only what I mined" mode.

## Choosing the chest
- `/loadchest set` – save the chest you are looking at
- `/loadchest set <x> <y> <z>` – save a chest by coordinates
- `/loadchest at <x> <y> <z> [filter]` – one-off, use this chest without saving
- `/loadchest nearest [filter]` – ignore the saved chest, use the nearest one
- `/loadchest clear` – forget the saved chest

The saved chest is stored in `config/loadchest.json` (no dimension check – use it in the dimension where you saved it).

## Loading
- `/loadchest` – deposit everything except what the keep rules protect
- `/loadchest <text>` – only items whose id contains the text (e.g. `cobblestone`, `diamond`)
- `/loadchest mined [text]` – only deposit items gained since the last `/loadchest baseline`
- `/loadchest baseline` – remember the current inventory (run it before you start mining)
- `/loadchest stop` – abort

## Keep rules
- `/loadchest keep <item> <count>` – always keep that many; the rest is deposited (`dirt 128`)
- `/loadchest keep <item> -1` – never deposit this item (`-1` = keep all)
- `/loadchest keep remove <item>` / `/loadchest keep list`
- `<item>` is matched as part of the item id, e.g. `dirt`, `_pickaxe`, `torch`. The special name `@food` matches every food item. Write names without `minecraft:`.

Keep limits count the **whole inventory including the hotbar**, so keeping 128 dirt works even if some of it sits in the hotbar. Default rules: `dirt 128`, `_pickaxe` (all), `@food` (all).

## Hotbar
- `/loadchest hotbar off` (default) – hotbar slots are never touched
- `/loadchest hotbar on` – hotbar stacks may be deposited too (keep rules still apply)

Items are taken from the main inventory first; the hotbar is only used if enabled and the keep limit is still not met.

## Build
1. Download `baritone-api-fabric-<version>.jar` matching your Minecraft version from the Baritone releases and put it in `libs/`.
2. Adjust the versions in `gradle.properties` and the jar name in `build.gradle`.
3. Add `settings.gradle` and the Gradle wrapper, then `./gradlew build` -> jar in `build/libs/`.
4. Put the jar in `mods/` together with Fabric API and the Baritone Fabric jar.

## Notes
- Whole stacks are shift-clicked; a stack that must be split (e.g. keep 20 of 64) is moved with pick-up/put-back clicks, one click every 2 ticks.
- Items the chest has no room for are skipped.
- Mappings are Yarn names for 1.21.1; other versions may need small renames.
