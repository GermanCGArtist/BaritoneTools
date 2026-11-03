# Baritone Queue - Baritone addon (Fabric 26.1.1)

Type a list of Baritone commands and run them **one after another**: the next line starts when the previous task is finished.

Example: clear the trees, then the area.
```
sel replace oak_log air
sel replace birch_log air
sel ca
```

## Use
- Press **J** (changeable in Options > Controls > Baritone Queue) to open the editor. One command per line, with or without `#`. **Run** starts the list, **Stop** cancels it, **Skip step** cancels the running task and goes on with the next line.
- Or in chat: `/queue add <command>`, `/queue list`, `/queue run`, `/queue stop`, `/queue skip`, `/queue remove <n>`, `/queue clear`, `/queue open`.
- The script is saved in `config/baritonequeue.json`; running it is always started by hand.

## Completion and Test
- While you type, the suggestions from **Baritone's own tab completion** appear below the text (`sel re` offers `replace`, `mine di` offers the matching blocks). **Tab** takes the first suggestion, a click takes any.
- **Test** checks the script without running anything and lists the problems (also in chat): unknown commands (with "did you mean"), block names that do not exist, `sel` commands without a selection (lines above that set `sel pos1`/`pos2` count), `wait` without a number, and lines that never finish (`mine` without a number, `farm`, `follow`, `explore`) with more lines below. It cannot know whether the world lets a command succeed, only whether the script is in order.

## Code editor
- Colours: commands **blue** (unknown ones **red**), `sel` subcommands purple, block names teal, numbers light green, `wait` yellow, `/commands` orange, `// comments` green. Line numbers on the left.
- **Comments:** `// text` on its own line or after a command (`sel ca // clear it`). **Ctrl+/** puts `//` in front of the selected lines (or the cursor line) and takes it away again.

## Presets
- Select some lines (or none: the whole script is saved), type a name, **Save preset**. Saving under an existing name replaces it.
- **Presets...** lists them: **Insert** puts one into the script at the cursor line (what was there moves below; a blank cursor line is replaced), **X** deletes it. Hover a preset to see its lines. Build bigger scripts by inserting presets one after another: clear trees, replace water and lava, mine area, ...
- Three presets come with it: *Clear trees* (all logs, then all leaves), *Replace water and lava* (with cobblestone: change the last word), *Mine area* (`sel ca`). Edit and re-save them under the same name to change them.
- Presets are saved in `config/baritonequeue.json`.

## Lines
| Line | |
|---|---|
| `sel ca`, `mine diamond_ore`, `goto 10 64 10`, ... | any Baritone command (the `#` is optional) |
| `wait 30` | wait 30 seconds |
| `/loadchest` | a client or server command (counts as instantly done) |
| `// text` | comment |

## When does a step count as finished?
When no Baritone task has been running for 2 seconds. A command that never ends (for example `mine` without a limit) blocks the lines after it: use **Skip step** or `/queue skip`.

## It stops when
- you type `#stop` or `#cancel`, or press **Stop**
- Baritone does not understand a line (it tells you which)
- another addon stops the task: BuildTools' safe-area stop or its water-loop stop

`#p` holds the queue, `#r` continues it.

## Build
Run `build.bat` (portable JDK 25 from `../../tools`) -> `build/libs/baritonequeue-<version>.jar`.
Put it in `mods/` with Fabric API and **`baritone-api-fabric-1.18.0.jar`**.

## After a death
With SurvivalKit installed, a death does not end the queue: once SurvivalKit has you healthy and safe again, the current step is run again. Without it (or after repeated deaths) the queue stops with a message.

## Progress (1.4.0)
While the queue runs, a bar shows on screen (and in the editor): one piece per step, the share of the whole script, and the running step with percent and time left. `sel ca`, `sel replace`, `sel set` count the blocks still to change; `goto x y z` uses the distance; `wait` the clock; other steps count when finished. In the editor, line numbers turn green (done) and blue (running).

## Scripting (1.5.0)
```
repeat 3
  sel replace oak_log air
  wait 10
end
if full
  /loadchest now
else
  mine 64 iron_ore
end
wait until night
timeout 600      // later steps give up after 10 minutes (timeout off clears it)
```
Conditions: `full`, `night`, `day`, `raining`, `thundering`, `has <item> [n]`, `health|food below|above <n>`, each with `not`. `wait until day|night|clear|HH:MM`. Presets can be copied, pasted, exported to and imported from config/baritonequeue-presets.txt (Presets... screen). Default presets include a few profiles (AFK mining, building).
