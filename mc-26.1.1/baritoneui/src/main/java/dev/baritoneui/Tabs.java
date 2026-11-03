package dev.baritoneui;

import baritone.api.Settings;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Builds the rows of each tab. Labels are short; the hover text explains. */
final class Tabs {
    private Tabs() {}

    /** The settings people actually flip: name, short label, explanation. Names missing in this Baritone version are skipped. */
    private static final String[][] COMMON = {
            // movement
            {"allowBreak", "Break blocks", "Baritone may mine blocks that are in its way. Off = it only walks around them."},
            {"allowPlace", "Place blocks", "Baritone may place blocks to bridge, scaffold or pillar. Off = it never builds under its own feet."},
            {"allowSprint", "Sprint", "Sprint while walking."},
            {"allowParkour", "Parkour", "Jump across gaps."},
            {"allowParkourPlace", "Parkour + blocks", "Parkour jumps that need a block placed mid-jump. Fast, but looks suspicious on servers with anti-cheat."},
            {"allowParkourAscend", "Parkour upwards", "Jump across gaps that also go up one block."},
            {"allowDiagonalDescend", "Diagonal descend", "Walk diagonally down a step."},
            {"allowDiagonalAscend", "Diagonal ascend", "Walk diagonally up a step."},
            {"allowDownward", "Dig down", "Allow mining straight down."},
            {"allowVines", "Climb vines", "Use vines and ladders-like blocks to climb."},
            {"allowWaterBucketFall", "Water-bucket falls", "Survive long drops by placing water below. Needs a water bucket in the hotbar."},
            {"assumeWalkOnWater", "Walk on water", "Treat water as solid ground. Only turn on with frost walker, otherwise Baritone sinks."},
            {"assumeWalkOnLava", "Walk on lava", "Treat lava as solid ground. Dangerous, leave it off."},
            {"maxFallHeightNoWater", "Max fall", "Highest drop Baritone accepts without water below. Each block above 3 costs health."},
            {"maxFallHeightBucket", "Max fall (bucket)", "Highest drop Baritone accepts when it carries a water bucket."},
            // mining / items
            {"mineScanDroppedItems", "Mine: grab drops", "While mining, also walk to dropped items."},
            {"legitMine", "Legit mine", "Only mine ores that are visible from open air, like a player without x-ray."},
            {"legitMineIncludeDiagonals", "Legit: diagonals", "Legit mine also counts ores that only touch air at a corner."},
            {"pauseMiningForFallingBlocks", "Wait for falling blocks", "Pause mining while sand or gravel above is still falling."},
            {"itemSaver", "Save tools", "Stop using a tool shortly before it breaks (see 'Save-tool limit'). SurvivalKit then swaps in a fresh one."},
            {"itemSaverThreshold", "Save-tool limit", "Durability left at which a tool counts as worn out and is no longer used."},
            {"allowInventory", "Use inventory", "Baritone may move items inside your inventory, for example tools into the hotbar."},
            {"autoTool", "Auto tool", "Pick the best tool for each block automatically."},
            // building / farming
            {"buildInLayers", "Build in layers", "Build one layer at a time from the bottom up."},
            {"buildIgnoreExisting", "Build: keep existing", "Don't replace blocks that are already there, even if they differ from the plan."},
            {"replantCrops", "Replant crops", "Farming: plant a new seed after harvesting."},
            {"replantNetherWart", "Replant nether wart", "Farming: replant nether wart too."},
            {"rightClickContainerOnArrival", "Open chests on arrival", "When the goal is a container, open it once Baritone gets there."},
            // behaviour
            {"antiCheatCompatibility", "Anti-cheat mode", "Behave closer to a normal player so servers with anti-cheat are less likely to flag you."},
            {"randomLooking", "Random looking", "Small random head movements, so you look less robotic."},
            {"freeLook", "Free look", "Baritone turns its view without touching your own camera direction."},
            {"cancelOnGoalInvalidation", "Stop on bad goal", "Cancel the path when its goal stops making sense, for example the target block is already gone."},
            {"backfill", "Backfill", "Fill in the tunnel behind you with blocks so nothing can follow."},
            // display
            {"chatControl", "Chat commands (#)", "Allow Baritone commands typed in chat with the # prefix."},
            {"renderPath", "Show path", "Draw the planned path in the world."},
            {"renderGoal", "Show goal", "Draw the goal in the world."},
            {"desktopNotifications", "Desktop notices", "Send desktop notifications when Baritone has something to say."},
    };

    static List<Row> common() {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.tip(Row.action("Stop task", "Stop (#stop)", Backend::stopEverything),
                "Cancels whatever Baritone is doing right now, like typing #stop."));
        if (Backend.loaded("baritonequeue")) {
            rows.add(Row.tip(Row.action("Command queue", "Open editor", () -> Backend.command("queue open")),
                    "Type a list of Baritone commands and run them one after another."));
        }
        for (String[] c : COMMON) {
            Settings.Setting<?> s = Backend.settings().byLowerName.get(c[0].toLowerCase());
            if (s != null && supported(s)) rows.add(setting(s, c[1], c[2]));
        }
        return rows;
    }

    static List<Row> all() {
        List<Row> rows = new ArrayList<>();
        Backend.settings().allSettings.stream()
                .filter(Tabs::supported)
                .sorted(Comparator.comparing(s -> s.getName().toLowerCase()))
                .forEach(s -> rows.add(setting(s, humanize(s.getName()), null)));
        return rows;
    }

    private static boolean supported(Settings.Setting<?> s) {
        Class<?> c = s.getValueClass();
        return c == Boolean.class || c == Integer.class || c == Long.class || c == Double.class || c == Float.class;
    }

    /** "pauseMiningForFallingBlocks" -> "Pause mining for falling blocks". */
    private static String humanize(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) b.append(' ');
            b.append(i == 0 ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
        }
        return b.toString();
    }

    private static Row setting(Settings.Setting<?> s, String label, String tip) {
        String name = s.getName();
        String full = (tip == null ? "" : tip + " ") + "[Baritone setting '" + name + "', default " + s.defaultValue + "; "
                + "in chat: #set " + name + "]";
        Row row = s.getValueClass() == Boolean.class
                ? Row.bool(label, () -> Boolean.TRUE.equals(s.value), v -> Backend.applySetting(name, v.toString()))
                : Row.number(label, () -> String.valueOf(s.value), text -> Backend.applySetting(name, text));
        return Row.tip(row, full);
    }

    // ------------------------------------------------------------------ LoadChest

    static List<Row> loadChest() {
        if (!Backend.loaded("loadchest")) return List.of(Row.info("The LoadChest mod is not installed."));
        Supplier<JsonObject> cfg = () -> Backend.json("loadchest.json");
        List<Row> rows = new ArrayList<>();

        String[] modes = {"off", "mined", "all"};
        rows.add(Row.tip(Row.cycle("Pick up items", List.of("Off", "Only my mined", "All items"),
                () -> {
                    JsonObject o = cfg.get();
                    return !Backend.bool(o, "pickup") ? 0 : Backend.bool(o, "pickupAll") ? 2 : 1;
                },
                i -> Backend.command("loadchest pickup " + modes[i])),
                "Off: never walk to dropped items. Only my mined: only drops from blocks Baritone broke (mob drops and item streams are left alone). All items: every drop nearby."));
        rows.add(Row.tip(Row.bool("Auto-empty",
                () -> Backend.bool(cfg.get(), "auto"),
                v -> Backend.command("loadchest auto " + (v ? "on" : "off"))),
                "When the inventory is full, go to the deposit chest, empty it, then carry on with the task."));
        rows.add(Row.tip(Row.bool("Empty at base",
                () -> Backend.bool(cfg.get(), "nearBase"),
                v -> Backend.command("loadchest nearbase " + (v ? "on" : "off"))),
                "When Baritone passes near the chests or bed anyway and you carry 8+ stacks of loot, empty into the chest on the way instead of waiting until the inventory is full."));
        rows.add(Row.tip(Row.bool("Include hotbar",
                () -> Backend.bool(cfg.get(), "includeHotbar"),
                v -> Backend.command("loadchest hotbar " + (v ? "on" : "off"))),
                "Also deposit items from the hotbar. Tools and the keep rules still stay."));
        rows.add(Row.info("Deposit chest: " + Backend.pos(cfg.get(), "chest")));
        rows.add(Row.tip(Row.action("Deposit chest", "Set chest", () -> Backend.command("loadchest set")),
                "Saves the chest you are looking at as the place mined items are put."));
        rows.add(Row.tip(Row.action("Empty now", "Load chest", () -> Backend.command("loadchest")),
                "Go to the deposit chest and empty the inventory right now."));
        rows.add(Row.tip(Row.action("Cancel trip", "Stop", () -> Backend.command("loadchest stop")),
                "Cancels a running chest trip."));
        rows.add(Row.tip(Row.action("Baseline", "Baseline", () -> Backend.command("loadchest baseline")),
                "Remembers what you carry now, for the 'mined' deposit command (only what you gained since then is deposited)."));

        JsonObject keep = cfg.get().has("keep") && cfg.get().get("keep").isJsonObject() ? cfg.get().getAsJsonObject("keep") : new JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : keep.entrySet()) {
            String item = e.getKey();
            int count = e.getValue().getAsInt();
            rows.add(Row.tip(Row.removable("Keep: " + item, () -> String.valueOf(count),
                    text -> {
                        int n = Integer.parseInt(text);
                        if (n < -1) throw new NumberFormatException();
                        Backend.command("loadchest keep " + Backend.quote(item) + " " + n);
                    },
                    () -> Backend.command("loadchest keep remove " + Backend.quote(item))),
                    "How many of '" + item + "' stay in your inventory when emptying (-1 = all of them). The X removes the rule. Tools and SurvivalKit's restock items are always kept."));
        }
        rows.add(Row.tip(Row.addRule("Add keep rule", "64",
                (item, n) -> Backend.command("loadchest keep " + Backend.quote(item) + " " + n)),
                "Type an item name (or part of it, or 'food') and how many to keep, then Add."));
        return rows;
    }

    // ------------------------------------------------------------------ SurvivalKit

    static List<Row> survivalKit() {
        if (!Backend.loaded("survivalkit")) return List.of(Row.info("The SurvivalKit mod is not installed."));
        Supplier<JsonObject> cfg = () -> Backend.json("survivalkit.json");
        List<Row> rows = new ArrayList<>();

        rows.add(toggle(cfg, "Auto-eat", "eat", "eat",
                "Eat when hungry or hurt, so you stay at full health."));
        rows.add(toggle(cfg, "Eat while walking", "walkEat", "walkeat",
                "On plain walking stretches, eat without stopping Baritone (it keeps its path). Not used when mining, placing or swimming, and not when the next stop is under 3 s away and nothing is urgent."));
        rows.add(toggle(cfg, "Golden apples", "golden", "golden",
                "Eat a golden apple when health is low and the food bar is already full."));
        rows.add(Row.tip(Row.number("Eat at food", () -> String.valueOf(Backend.num(cfg.get(), "eatBelow", 14)),
                text -> {
                    int n = Integer.parseInt(text);
                    if (n < 1 || n > 19) throw new NumberFormatException();
                    Backend.command("survival eatbelow " + n);
                }),
                "Eat when the food bar is at this value (out of 20) or lower. Being hurt also makes it eat."));
        rows.add(toggle(cfg, "Defence", "defence", "defence",
                "Fight hostile mobs that come close, then go back to the task."));
        rows.add(toggle(cfg, "Auto-respawn", "respawn", "respawn",
                "Click Respawn after dying and warn in chat where you died."));
        rows.add(toggle(cfg, "Resume after death", "resume", "resume",
                "When you die with a task running (#mine, #sel ca, a queue...), get healthy again, wait until no monster is near, then run the same command again. Gives up after 3 deaths in 10 minutes."));
        rows.add(toggle(cfg, "Bed before dusk", "bedPlan", "bedplan",
                "Works out when to leave for the bed from its distance, so you arrive at dusk, before mobs spawn, and wait there until you can sleep. Off: it only goes once it is already night."));
        rows.add(Row.tip(Row.number("Bed slack (s)", () -> String.valueOf(Backend.num(cfg.get(), "bedLead", 20)),
                text -> {
                    int n = Integer.parseInt(text);
                    if (n < 0 || n > 600) throw new NumberFormatException();
                    Backend.command("survival bedlead " + n);
                }),
                "Extra seconds added to the walking time to the bed (eating, restocking, detours). More = leaves earlier."));
        rows.add(toggle(cfg, "Bed in thunderstorms", "weather", "weather",
                "A thunderstorm counts like night: monsters spawn in daylight and beds work, so go to bed."));
        rows.add(toggle(cfg, "Auto-reconnect", "reconnect", "reconnect",
                "If the server drops the connection, reconnect after a growing pause (10 s up to 5 min, 8 tries) and carry on with the task or the queue. Leaving through the menu yourself does nothing."));
        rows.add(toggle(cfg, "Auto-sleep", "sleep", "sleep",
                "At night, walk to the saved bed and sleep, which also stops phantoms."));
        rows.add(toggle(cfg, "Auto-restock", "restock", "restock",
                "When food or tools run low, fetch more from the supply chest (and put worn tools in the return chest)."));
        rows.add(toggle(cfg, "Top-up at base", "topUp", "topup",
                "When Baritone passes near the chests or bed anyway, also refill stock that is only somewhat low (under 75%), so no extra trip is needed later."));
        rows.add(Row.tip(Row.number("Base radius", () -> String.valueOf(Backend.num(cfg.get(), "baseRadius", 24)),
                text -> {
                    int n = Integer.parseInt(text);
                    if (n < 4 || n > 96) throw new NumberFormatException();
                    Backend.command("survival baseradius " + n);
                }),
                "How close (in blocks) to a chest or the bed counts as 'at the base' for top-ups."));
        rows.add(toggle(cfg, "Run in background", "background", "background",
                "Keep working when the window loses focus, for example on a second monitor."));
        rows.add(toggle(cfg, "Act when idle", "always", "always",
                "Off (default): only act while a Baritone task is running. On: always act."));

        rows.add(Row.info("Bed: " + Backend.pos(cfg.get(), "bed")));
        rows.add(Row.tip(Row.action("Bed", "Set bed", () -> Backend.command("survival bed set")),
                "Saves the bed you are looking at."));
        rows.add(Row.tip(Row.action("Sleep now", "Sleep now", () -> Backend.command("survival sleep now")),
                "Go to bed now, if it is night."));
        rows.add(Row.info("Bed pace: " + String.format("%.1f", cfg.get().has("ticksPerBlock") ? cfg.get().get("ticksPerBlock").getAsDouble() : 5.5) + " ticks per block (learned)"));
        rows.add(Row.info("Supply chest: " + Backend.pos(cfg.get(), "supply")));
        rows.add(Row.tip(Row.actionRefresh("Extra supply chest", "Add chest", () -> Backend.command("survival supply add")),
                "Adds the chest you are looking at as another supply chest. The restock visits the one that has what is missing first (it remembers what it saw in each chest)."));
        rows.add(Row.tip(Row.action("Remove supply chest", "Remove chest", () -> Backend.command("survival supply remove")),
                "Removes the chest you are looking at from the supply chests."));
        rows.add(Row.tip(Row.action("Chests I know", "List chests", () -> Backend.command("survival chests")),
                "Prints what was in every chest that was opened (nearest first). '/survival find <item>' tells where something is."));
        rows.add(Row.tip(Row.action("Session report", "Show report", () -> Backend.command("survival report")),
                "Time, distance walked, meals, restocks, sleeps, deaths and disconnects since the game started (or the last reset). Also saved to config/survivalkit-sessions.log on a disconnect."));
        rows.add(Row.tip(Row.action("Supply chest", "Set chest", () -> Backend.command("survival supply set")),
                "Saves the chest you are looking at as the place food and tools are taken from."));
        rows.add(Row.tip(Row.action("Restock now", "Restock now", () -> Backend.command("survival restock now")),
                "Go to the supply chest and refill from the want-list right now."));
        rows.add(Row.info("Return chest: " + Backend.pos(cfg.get(), "returnChest")));
        rows.add(Row.tip(Row.action("Return chest", "Set chest", () -> Backend.command("survival return set")),
                "Saves the chest you are looking at as the place worn-out tools are handed in."));
        rows.add(Row.tip(Row.action("Forget return chest", "Clear", () -> Backend.command("survival return clear")),
                "Worn tools are then kept in your inventory."));

        JsonObject want = cfg.get().has("want") && cfg.get().get("want").isJsonObject() ? cfg.get().getAsJsonObject("want") : new JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : want.entrySet()) {
            String item = e.getKey();
            int count = e.getValue().getAsInt();
            rows.add(Row.tip(Row.removable("Carry: " + item, () -> String.valueOf(count),
                    text -> {
                        int n = Integer.parseInt(text);
                        if (n < 1 || n > 2304) throw new NumberFormatException();
                        Backend.command("survival want " + Backend.quote(item) + " " + n);
                    },
                    () -> Backend.command("survival want remove " + Backend.quote(item))),
                    "Keep this many of '" + item + "' on you; restock when below half. The X removes the rule."));
        }
        rows.add(Row.tip(Row.action("Work items", "Open editor",
                () -> net.minecraft.client.Minecraft.getInstance().setScreen(new WantScreen(net.minecraft.client.Minecraft.getInstance().screen))),
                "Edit everything to carry in a text editor: item, count and optional enchantments, like 'minecraft:diamond_pickaxe 2 efficiency 5;mending'. Colours and Tab completion help; Test checks every line."));
        rows.add(Row.tip(Row.addRule("Add to carry list", "16",
                (item, n) -> Backend.command("survival want " + Backend.quote(item) + " " + Math.max(1, n))),
                "Type an item name (or part of it, or 'food') and how many to carry, then Add. 'diamond_pickaxe' keeps your pickaxe stocked."));
        return rows;
    }

    // ------------------------------------------------------------------ BuildTools

    static List<Row> buildTools() {
        if (!Backend.loaded("buildtools")) return List.of(Row.info("The BuildTools mod is not installed."));
        Supplier<JsonObject> cfg = () -> Backend.json("buildtools.json");
        List<Row> rows = new ArrayList<>();
        rows.add(Row.tip(Row.bool("Auto-drain",
                () -> Backend.bool(cfg.get(), "drainAuto"),
                v -> Backend.command("buildtools drain " + (v ? "on" : "off"))),
                "When the builder pauses because of flowing water, plug or remove the water and resume."));
        rows.add(Row.tip(Row.bool("Dam",
                () -> Backend.bool(cfg.get(), "dam"),
                v -> Backend.command("buildtools dam " + (v ? "on" : "off"))),
                "Also plug water just outside your selection, so it can't flow back in."));
        rows.add(Row.tip(Row.bool("Water escape",
                () -> Backend.bool(cfg.get(), "escape"),
                v -> Backend.command("buildtools escape " + (v ? "on" : "off"))),
                "When Baritone is in water and walks in circles without getting anywhere, swim to the nearest dry land, then carry on. If it happens 3 times in 5 minutes, the task is stopped."));
        rows.add(Row.tip(Row.action("Drain now", "Drain now", () -> Backend.command("buildtools drain now")),
                "Remove water in the selection right now."));
        rows.add(Row.tip(Row.action("Fill with water", "Fill selection", () -> Backend.command("buildtools fill")),
                "Select the area with #sel pos1 / pos2 first. Carry a bucket and blocks. Open sides are sealed, then the area is filled with water."));
        rows.add(Row.tip(Row.action("Cancel", "Stop", () -> Backend.command("buildtools stop")),
                "Cancels a running drain or fill."));

        // safe areas: Baritone can walk through them but never break or place blocks inside
        rows.add(Row.tip(Row.bool("Safe areas",
                () -> Backend.bool(cfg.get(), "protect"),
                v -> Backend.command("buildtools protect " + (v ? "on" : "off"))),
                "Baritone can walk through safe areas but can't break or place blocks inside. Off = areas are kept but not enforced."));
        rows.add(Row.tip(Row.bool("Spare chests, hoppers...",
                () -> Backend.bool(cfg.get(), "avoidBlocks"),
                v -> Backend.command("buildtools protect avoid " + (v ? "on" : "off"))),
                "While safe areas are on, Baritone plans around chests, hoppers, beds, furnaces and redstone parts everywhere (it never breaks them), so nothing stalls on them."));
        rows.add(Row.tip(Row.bool("Cut #sel around areas",
                () -> Backend.bool(cfg.get(), "trim"),
                v -> Backend.command("buildtools protect trim " + (v ? "on" : "off"))),
                "A #sel selection that overlaps a safe area is cut around it automatically, so #sel ca / #sel replace never aim at blocks inside the area."));
        rows.add(Row.tip(Row.actionRefresh("Protect #sel", "Protect selection", () -> Backend.command("buildtools protect add")),
                "Saves the current #sel selection as a safe area. It is copied, so clearing #sel afterwards does not remove it."));
        rows.add(Row.tip(Row.actionRefresh("Protect sites", "Protect sites", () -> Backend.command("buildtools protect sites 3")),
                "Protects 3 blocks around your saved chests and bed."));
        rows.add(Row.tip(Row.actionRefresh("Protect around me", "Protect here", () -> Backend.command("buildtools protect here 8")),
                "Protects a cube of 8 blocks in every direction around you."));
        rows.add(Row.info("Corner 1: " + Backend.pos(cfg.get(), "corner1")));
        rows.add(Row.tip(Row.actionRefresh("Corner 1", "Set corner 1", () -> Backend.command("buildtools protect pos1")),
                "Sets corner 1 to the block you look at (or where you stand). Independent of #sel."));
        rows.add(Row.info("Corner 2: " + Backend.pos(cfg.get(), "corner2")));
        rows.add(Row.tip(Row.actionRefresh("Corner 2", "Set corner 2", () -> Backend.command("buildtools protect pos2")),
                "Sets corner 2 to the block you look at (or where you stand)."));
        rows.add(Row.tip(Row.actionRefresh("Save corners", "Save area", () -> Backend.command("buildtools protect save")),
                "Turns corner 1 and 2 into a safe area. For exact numbers use /buildtools protect box x1 y1 z1 x2 y2 z2."));
        JsonObject all = cfg.get();
        if (all.has("zones") && all.get("zones").isJsonArray()) {
            for (com.google.gson.JsonElement e : all.getAsJsonArray("zones")) {
                JsonObject z = e.getAsJsonObject();
                String name = z.get("name").getAsString();
                String where = z.get("x1").getAsInt() + " " + z.get("y1").getAsInt() + " " + z.get("z1").getAsInt()
                        + " to " + z.get("x2").getAsInt() + " " + z.get("y2").getAsInt() + " " + z.get("z2").getAsInt();
                rows.add(Row.tip(Row.removeButton("Safe: " + name, () -> Backend.command("buildtools protect remove " + name)),
                        "Safe area '" + name + "': " + where));
                rows.add(Row.tip(Row.cycle("  " + name + ": mining", List.of("Denied", "Allowed"),
                        () -> Backend.bool(z, "allowBreak") ? 1 : 0,
                        i -> Backend.command("buildtools protect mining " + name + (i == 1 ? " allow" : " deny"))),
                        "Whether Baritone may break blocks in '" + name + "' and while you are inside it (or within reach, see 'near'). Denied: it plans around it."));
                rows.add(Row.tip(Row.cycle("  " + name + ": bridging", List.of("Denied", "Allowed"),
                        () -> Backend.bool(z, "allowPlace") ? 1 : 0,
                        i -> Backend.command("buildtools protect bridging " + name + (i == 1 ? " allow" : " deny"))),
                        "Whether Baritone may place blocks (bridge, scaffold, pillar) in '" + name + "' and while you are inside it (or within reach)."));
                rows.add(Row.tip(Row.bool("  " + name + ": also when near",
                        () -> Backend.bool(z, "reach") || !z.has("reach"),
                        v -> Backend.command("buildtools protect reach " + name + (v ? " on" : " off"))),
                        "On: the two rules above also apply while you stand within reach of the area (a few blocks from its edge), not only inside it. Baritone then can't dig or build into it from outside."));
            }
        }
        return rows;
    }

    private static Row toggle(Supplier<JsonObject> cfg, String label, String jsonKey, String command, String tip) {
        return Row.tip(Row.bool(label, () -> Backend.bool(cfg.get(), jsonKey),
                v -> Backend.command("survival " + command + " " + (v ? "on" : "off"))), tip);
    }
}
