# LoadChest – Baritone addon (Fabric 26.1.1)

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

## Automatic mode (during any Baritone task)
- `/loadchest auto on|off` – when the inventory is full during any Baritone task (`#mine`, `#farm`, `#build`, `#sel ca`, `#goto`, ...), Baritone pauses it, walks to the chest, deposits everything except the keep rules, and then the task continues by itself.
  If the inventory is still full afterwards (chest full, or everything is kept), mining stops and auto mode turns off.
- `/loadchest pickup off|mined|all` – **mined** (same as `on`, the default) collects only items that drop from blocks Baritone breaks; **all** collects every item nearby, mob drops included; **off** disables it. Details of *mined*: during any Baritone task, collect the items that drop from blocks **Baritone itself breaks** (within 16 blocks). Mob drops, items other players dropped and items in a water/ice item-transport line are never touched: only items that appear right where a block vanished while Baritone was breaking something count. To keep the task fast it collects in batches: once 8 drops (at least 3 s old) have piled up, or one has been lying around 20 s, it collects them all and then the task continues. When the task ends it does a final sweep. Items that can't be reached within 6 s, or that stay on the ground while in reach (pickup delay, no room), are skipped.
- `/loadchest debug on|off` – shows in chat which item it targets and why it skips one (useful for bug reports).

Both settings are saved. Typical setup: look at the chest, `/loadchest set`, `/loadchest auto on`, `/loadchest pickup on`, then `#mine diamond_ore`.
Both work as temporary Baritone processes, so `#stop` also cancels a chest trip, and a chest trip no longer cancels `#mine`.

## Keep rules
- `/loadchest keep <item> <count>` – always keep that many; the rest is deposited (`dirt 128`)
- `/loadchest keep <item> -1` – never deposit this item (`-1` = keep all)
- `/loadchest keep remove <item>` / `/loadchest keep list`
- `<item>` is matched as part of the item id, e.g. `dirt`, `_pickaxe`, `torch`. The special name `food` (stored as `@food`, also accepted as `"@food"` in quotes) matches every food item. Write names without `minecraft:`.

Keep limits count the **whole inventory including the hotbar**, so keeping 128 dirt works even if some of it sits in the hotbar. Default rules: `dirt 128`, `_pickaxe` (all), `@food` (all).

## Hotbar
- `/loadchest hotbar off` (default) – hotbar slots are never touched
- `/loadchest hotbar on` – hotbar stacks may be deposited too (keep rules still apply)

Items are taken from the main inventory first; the hotbar is only used if enabled and the keep limit is still not met.

## Build
1. `libs/baritone-api-fabric-1.18.0.jar` (Baritone v1.18.0 = MC 26.1) is already in place.
2. Run `build.bat` (uses the portable JDK 25 in `../../tools`) -> jar in `build/libs/`.
3. Put the jar in `mods/` together with Fabric API (0.145.4+26.1.1) and **`baritone-api-fabric-1.18.0.jar`**.
   The `baritone-standalone` jar does not work with addons: its API classes are obfuscated.

## Notes
- Whole stacks are shift-clicked; a stack that must be split (e.g. keep 20 of 64) is moved with pick-up/put-back clicks, one click every 2 ticks.
- Items the chest has no room for are skipped.
- MC 26.1 is unobfuscated, so the code uses official Mojang names (no Yarn).
