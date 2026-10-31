# SurvivalKit – Baritone addon (Fabric 26.1.1)

Keeps a long Baritone task alive: **eats**, **fights nearby mobs**, **sleeps through the night** and **restocks supplies** from a chest. Works next to any Baritone task (`#mine`, `#farm`, `#build`, `#sel ca`, `#goto`, ...) and next to LoadChest.

Every feature is a temporary Baritone process: your task is paused, the feature does its job, and the task continues by itself. `#stop` cancels everything.

Built to be server-friendly: vanilla timing only (hold-to-eat, full weapon cooldown, normal reach, line of sight, smooth turning at most 40°/tick), no packet tricks.

## Quick start
1. Look at your bed: `/survival bed set`
2. Look at a chest with your supplies: `/survival supply set`
3. Say what you want to carry: `/survival want food 16`, `/survival want torch 64`, `/survival want _pickaxe 2`
4. Start your task, e.g. `#mine diamond_ore`

## Commands
| Command | |
|---|---|
| `/survival` or `/survival status` | show settings |
| `/survival eat on\|off` | auto-eat |
| `/survival eatbelow <1-19>` | eat when the food bar is at or below this (default 14) |
| `/survival golden on\|off` | eat a golden apple at 4 hearts or less when the food bar is already full (default on) |
| `/survival background on\|off` | keep running when the game window loses focus (default on, see below) |
| `/survival debug on\|off` | extra messages about what it is doing |
| `/survival defence on\|off` | fight hostile mobs |
| `/survival sleep on\|off` / `sleep now` | sleep at night / go to bed right now |
| `/survival bed set [x y z]` / `bed clear` | the bed to sleep in |
| `/survival restock on\|off` / `restock now` | auto-restock / restock right now |
| `/survival supply set [x y z]` / `supply clear` | the supply chest (chest or barrel) |
| `/survival want <item> <count>` | carry this many (item id fragment, or `food` for any safe food) |
| `/survival want remove <item>` / `want list` | edit the want-list |

Settings are saved in `config/survivalkit.json`.

## What each feature does
**Eat** – Stays at full health: it eats whenever you are hurt and the food bar isn't full (natural healing needs a full-ish bar), and whenever the food bar is at the `eatbelow` level or lower. With a full food bar and 4 hearts or less it eats a golden apple instead. It picks the best safe food (not rotten flesh, spider eyes, pufferfish, raw chicken, golden apples, ...), holds right-click until done and puts the hotbar back like it was. Food in the main inventory is swapped in and out again.

**Defence** – Hostile mobs within 7 blocks are attacked: it equips the best sword or axe in the hotbar (or moves one into an empty hotbar slot), walks up to the mob and swings whenever the weapon is fully charged. Creepers that start to fuse are run away from. Neutral mobs (endermen, zombified piglins) are only fought once they are angry, and piglins are left alone while you wear gold armor (piglin brutes are always fought). Wardens and Withers are never attacked. A mob it can't kill or reach in 25 s is ignored.

**Sleep** – At night (overworld only), while a Baritone task is running, it walks to the saved bed, sleeps until morning and then carries on. If it can't sleep (monsters nearby, not night yet, server refuses) it tries again after a minute. On servers the night is only skipped if enough players sleep, but you wake up at dawn anyway.

**Restock** – Every rule on the want-list is checked every 2 seconds. When a stock is below **half** of the wanted amount (e.g. fewer than 8 of `@food 16`), it walks to the supply chest and tops **all** rules up to the wanted amount. Worn-out tools don't count as stock and are never taken.
- **Works with Baritone's item saver** (`#set itemSaver true`, `#set itemSaverThreshold <n>`): a tool counts as worn out at exactly the moment Baritone stops using it. The moment one hits that limit (and you carry fewer than wanted) a restock starts right away, within about a second, no matter how much of the rest is left. The worn tool is handed back into the supply chest (so you can repair it) *before* the new one is taken, so the replacement lands in the **hotbar**, where Baritone looks for tools. Without the item saver, a tool counts as worn at over 80% damage. If the chest can't fill a rule, that rule is skipped for 5 minutes.
- `<item>` matches part of the item id: `torch`, `_pickaxe`, `cooked_beef`, `golden_carrot`. Write names without `minecraft:`.
- `food` matches any safe food (it is stored as `@food`; `"@food"` in quotes works too).

## Second monitor / background use
- The bot keeps working with the **pause menu, chat or your inventory open**. Only a chest-type screen (or being dead) pauses it.
- By default it turns off vanilla's *pause when window loses focus*, so clicking onto another monitor doesn't open the pause menu. (`/survival background off` to leave that setting alone.)
- In a **singleplayer** world an open pause menu still freezes the whole game, that is vanilla. It tells you in chat if that happens.

## Problems are reported in chat
Whenever something stops it, you get a `[SurvivalKit] PROBLEM: ...` message (at most once a minute per problem), for example: no safe food while hungry/hurt, low health, no sword, can't kill a mob, no bed set at night, no supply chest set, supply chest short on something, inventory full, game paused, or an internal error. Errors never crash the game: the feature resets and carries on.

## Build
Run `build.bat` (portable JDK 25 from `../../tools`) -> `build/libs/survivalkit-<version>.jar`.
Put it in `mods/` with Fabric API and **`baritone-api-fabric-1.18.0.jar`** (not the standalone jar).

## Limits / notes
- Defence only fights `Monster` mobs: phantoms and slimes are not included.
- Restock and sleep only start while a Baritone task is running; `restock now` / `sleep now` work any time.
- Restock and sleep need the bed/chest to be loaded (within render distance) to start.
- Armor is not handled yet.
