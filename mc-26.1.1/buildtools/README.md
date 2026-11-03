# BuildTools – Baritone addon (Fabric 26.1.1)

Two helpers for Baritone's builder (`#build`, `#sel ca`, ...): it **removes water that makes the builder give up**, and it can **fill a selected area with water** using buckets.

## The water problem, and why buckets are not the answer
Baritone's builder can replace water *source* blocks (it places a block into them), but on *flowing* water it gives up: it prints "Unreplaceable liquids" and pauses itself. And water in Minecraft is **infinite**: any source with two source neighbours refills itself the moment you bucket it, and rivers and oceans never run dry. So this mod mostly **plugs** water with blocks, and only uses a bucket where that provably works.

## Drain (automatic)
The automatic drain only ever starts when Baritone's builder is running and has paused itself, so it does nothing while Baritone is idle. `drain now` and `fill` are manual.

When the builder pauses, BuildTools looks for water in your `#sel` selection (or within 8 blocks of you if there is no selection) and:
1. **Dam** – with a selection: plugs the water just *outside* the selection's edge with throwaway blocks, so nothing can flow back in once the builder clears the area. (`/buildtools dam off` to leave the outside alone.)
2. **Plug** – puts a throwaway block (cobblestone, dirt, ... = Baritone's `acceptableThrowawayItems`) into every flowing or refilling water cell inside the area.
3. **Bucket** – only for a *lone* source with fewer than two source neighbours (it really drains for good). Needs an empty bucket; otherwise it plugs it.
4. Looks again after a moment (water recedes slowly), repeats up to 3 times, then **resumes the builder**.

It places like a player: turns to the face of a neighbouring block (at most 40°/tick) and right-clicks, one click every few ticks.

## Fill an area with water
1. Select the area: `#sel pos1`, `#sel pos2` (look at the corners).
2. Carry a bucket (empty or full) and some throwaway blocks.
3. `/buildtools fill`

What happens:
1. **Seal** – water would run out through every open side, so first the open cells next to the area (sides and bottom) are closed with blocks. This uses Baritone's own builder, which knows how to bridge and scaffold. If something stops it (not enough blocks, unreachable spots) it tells you and does nothing else.
2. **Fill** – layer by layer from the bottom. First a source on every second cell (Minecraft turns a cell between two sources into a source), a pause while the water spreads, then the remaining gaps are placed directly.
3. The bucket is refilled from any source that regenerates (two source neighbours, so the supply never runs out) or from a pond within 16 blocks – for the very first bucket stand near water or carry a full bucket.

Not possible in the Nether (water evaporates). Selections are limited to 30,000 cells; fill bigger areas in parts.

## Safe areas
Mark the places Baritone must leave alone, such as your supply chest, return chest, bed or a whole storage room. Inside a safe area **Baritone can walk, open chests and use beds, but can never break or place a block**. Nothing is checked for you by hand or for the other addons, only for Baritone's own clicks.

Create them with:
- `/buildtools protect sites [radius]` – around the saved supply chest, return chest, bed (SurvivalKit) and deposit chest (LoadChest), default 3 blocks each way
- `/buildtools protect add [name]` – the current `#sel` selection
- `/buildtools protect here <radius> [name]` – a cube around you
- `/buildtools protect list` / `remove <name>` / `clear`, and `protect on|off`

How it is enforced: Minecraft's own "break block" and "place block" actions are vetoed whenever Baritone is the one clicking and the position is inside an area. Baritone's internals are scrambled in the release build, so its pathfinder can't be taught about the areas; it will still *plan* to break something in there. When that happens you get a chat message, and if it keeps trying for 3 seconds the **Baritone task is stopped** with an explanation (otherwise it would retry forever). So: keep the areas where your `#mine` / `#build` job doesn't need to dig, or turn the area off for that job.

## Commands
| Command | |
|---|---|
| `/buildtools` or `status` | show settings |
| `/buildtools drain on\|off` | automatic drain when the builder pauses (default on) |
| `/buildtools drain now` | remove water in the selection right now |
| `/buildtools dam on\|off` | also plug water just outside the selection (default on) |
| `/buildtools fill` | seal and fill the selection with water |
| `/buildtools protect ...` | safe areas, see above |
| `/buildtools stop` | cancel a running drain or fill (`#stop` works too) |

Everything is also in the **BuildTools** tab of BaritoneUI. Settings are saved in `config/buildtools.json`.

## Limits
- Interior cells of upper layers (nothing solid next to them) cannot be clicked; they fill because the water spreads in from the edges. If a cell stays dry it is reported and skipped.
- The drain can't remove an ocean or river, only wall it off. Water that keeps coming from a source you didn't select will come back when a plug is mined out: select the area with the water source in it, or turn the dam on.
- Problems are reported in chat as `[BuildTools] PROBLEM: ...` (at most once a minute per kind).

## Build
Run `build.bat` (portable JDK 25 from `../../tools`) -> `build/libs/buildtools-<version>.jar`.
Put it in `mods/` with Fabric API and **`baritone-api-fabric-1.18.0.jar`** (not the standalone jar).

## Setting corners without #sel
`/buildtools protect pos1` and `pos2` take the block you look at (or stand in); `protect save [name]` makes the area. `protect box x1 y1 z1 x2 y2 z2 [name]` takes exact numbers. Areas made from `#sel` are copied, so clearing `#sel` afterwards keeps them.


## Staying out without stalling
- **Spare chests, hoppers...** (`/buildtools protect avoid on|off`, default on): while safe areas are on, Baritone is told (its own `blocksToDisallowBreaking` setting) never to break chests, barrels, hoppers, furnaces, shulker boxes, beds, anvils, redstone parts and similar. It plans around them from the start, so it does not stall on them, anywhere.
- **Cut #sel around areas** (`/buildtools protect trim on|off`, default on): a `#sel` selection that overlaps a safe area is cut around it within a second, so `#sel ca` / `#sel replace` never aim at blocks inside the area. A selection completely inside an area is removed.
- What cannot be avoided: Baritone may still route *through* plain blocks (stone, dirt, ...) of an area when mining or walking. That is stopped by the veto and, after 3 seconds, the task is stopped with a message naming the block.

## Mining and bridging rules per area
Every safe area has three switches (`/buildtools protect list` shows them, they are also on the BuildTools tab):
- `/buildtools protect mining <name> allow|deny` - may Baritone break blocks (default: deny)
- `/buildtools protect bridging <name> allow|deny` - may Baritone place blocks: bridge, scaffold, pillar (default: deny)
- `/buildtools protect reach <name> on|off` - also apply both while you stand within reach of the area, not only inside it (default: on). "Reach" is 6 blocks from its edge: `/buildtools protect reachradius <1-12>`.

How: while you are inside or within reach of an area that denies something, Baritone's own `allowBreak` / `allowPlace` (and parkour placing) settings are switched off and switched back when you leave (your own values are remembered). Baritone's planner sees the setting, so it walks around instead of stalling. The click veto stays as a backstop for blocks inside an area.
