package dev.baritoneui;

import baritone.api.Settings;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Builds the rows of each tab. */
final class Tabs {
    private Tabs() {}

    /** The settings people actually flip; names that do not exist in this Baritone version are skipped. */
    private static final String[] COMMON = {
            // movement
            "allowBreak", "allowPlace", "allowSprint", "allowParkour", "allowParkourPlace", "allowParkourAscend",
            "allowDiagonalDescend", "allowDiagonalAscend", "allowDownward", "allowVines", "allowWaterBucketFall",
            "assumeWalkOnWater", "assumeWalkOnLava", "maxFallHeightNoWater", "maxFallHeightBucket",
            // mining / items
            "mineScanDroppedItems", "legitMine", "legitMineIncludeDiagonals", "pauseMiningForFallingBlocks",
            "itemSaver", "itemSaverThreshold", "allowInventory", "autoTool",
            // building / farming
            "buildInLayers", "buildIgnoreExisting", "replantCrops", "replantNetherWart", "rightClickContainerOnArrival",
            // behaviour
            "antiCheatCompatibility", "randomLooking", "freeLook", "cancelOnGoalInvalidation", "backfill",
            // display
            "chatControl", "renderPath", "renderGoal", "desktopNotifications",
    };

    static List<Row> common() {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.action("Stop the current Baritone task", "Stop (#stop)", Backend::stopEverything));
        for (String name : COMMON) {
            Settings.Setting<?> s = Backend.settings().byLowerName.get(name.toLowerCase());
            if (s != null && supported(s)) rows.add(setting(s));
        }
        return rows;
    }

    static List<Row> all() {
        List<Row> rows = new ArrayList<>();
        Backend.settings().allSettings.stream()
                .filter(Tabs::supported)
                .sorted(Comparator.comparing(s -> s.getName().toLowerCase()))
                .forEach(s -> rows.add(setting(s)));
        return rows;
    }

    private static boolean supported(Settings.Setting<?> s) {
        Class<?> c = s.getValueClass();
        return c == Boolean.class || c == Integer.class || c == Long.class || c == Double.class || c == Float.class;
    }

    private static Row setting(Settings.Setting<?> s) {
        String name = s.getName();
        if (s.getValueClass() == Boolean.class) {
            return Row.bool(name, () -> Boolean.TRUE.equals(s.value), v -> Backend.applySetting(name, v.toString()));
        }
        return Row.number(name, () -> String.valueOf(s.value), text -> Backend.applySetting(name, text));
    }

    // ------------------------------------------------------------------ LoadChest

    static List<Row> loadChest() {
        if (!Backend.loaded("loadchest")) return List.of(Row.info("The LoadChest mod is not installed."));
        Supplier<JsonObject> cfg = () -> Backend.json("loadchest.json");
        List<Row> rows = new ArrayList<>();

        String[] modes = {"off", "mined", "all"};
        rows.add(Row.cycle("Item collection (pickup)", List.of("Off", "Only items I mined", "All items"),
                () -> {
                    JsonObject o = cfg.get();
                    return !Backend.bool(o, "pickup") ? 0 : Backend.bool(o, "pickupAll") ? 2 : 1;
                },
                i -> Backend.command("loadchest pickup " + modes[i])));
        rows.add(Row.bool("Auto-empty when inventory is full",
                () -> Backend.bool(cfg.get(), "auto"),
                v -> Backend.command("loadchest auto " + (v ? "on" : "off"))));
        rows.add(Row.bool("Deposit hotbar items too",
                () -> Backend.bool(cfg.get(), "includeHotbar"),
                v -> Backend.command("loadchest hotbar " + (v ? "on" : "off"))));
        rows.add(Row.info("Deposit chest: " + Backend.pos(cfg.get(), "chest")));
        rows.add(Row.action("Save the chest I am looking at", "Set chest", () -> Backend.command("loadchest set")));
        rows.add(Row.action("Empty the inventory into it now", "Load chest", () -> Backend.command("loadchest")));
        rows.add(Row.action("Cancel a running chest trip", "Stop", () -> Backend.command("loadchest stop")));
        rows.add(Row.action("Remember inventory (for the 'mined' command)", "Baseline", () -> Backend.command("loadchest baseline")));

        JsonObject keep = cfg.get().has("keep") && cfg.get().get("keep").isJsonObject() ? cfg.get().getAsJsonObject("keep") : new JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : keep.entrySet()) {
            String item = e.getKey();
            int count = e.getValue().getAsInt();
            rows.add(Row.removable("Keep: " + item + " (-1 = all)", () -> String.valueOf(count),
                    text -> {
                        int n = Integer.parseInt(text);
                        if (n < -1) throw new NumberFormatException();
                        Backend.command("loadchest keep " + Backend.quote(item) + " " + n);
                    },
                    () -> Backend.command("loadchest keep remove " + Backend.quote(item))));
        }
        rows.add(Row.addRule("Add keep rule (item, count)", "64",
                (item, n) -> Backend.command("loadchest keep " + Backend.quote(item) + " " + n)));
        return rows;
    }

    // ------------------------------------------------------------------ SurvivalKit

    static List<Row> survivalKit() {
        if (!Backend.loaded("survivalkit")) return List.of(Row.info("The SurvivalKit mod is not installed."));
        Supplier<JsonObject> cfg = () -> Backend.json("survivalkit.json");
        List<Row> rows = new ArrayList<>();

        rows.add(toggle(cfg, "Auto-eat (stay at full health)", "eat", "eat"));
        rows.add(toggle(cfg, "Golden apple when health is low", "golden", "golden"));
        rows.add(Row.number("Also eat when food bar is at or below", () -> String.valueOf(Backend.num(cfg.get(), "eatBelow", 14)),
                text -> {
                    int n = Integer.parseInt(text);
                    if (n < 1 || n > 19) throw new NumberFormatException();
                    Backend.command("survival eatbelow " + n);
                }));
        rows.add(toggle(cfg, "Defence (fight hostile mobs)", "defence", "defence"));
        rows.add(toggle(cfg, "Sleep through the night", "sleep", "sleep"));
        rows.add(toggle(cfg, "Restock from the supply chest", "restock", "restock"));
        rows.add(toggle(cfg, "Keep running when window loses focus", "background", "background"));

        rows.add(Row.info("Bed: " + Backend.pos(cfg.get(), "bed")));
        rows.add(Row.action("Save the bed I am looking at", "Set bed", () -> Backend.command("survival bed set")));
        rows.add(Row.action("Go to bed now (if it is night)", "Sleep now", () -> Backend.command("survival sleep now")));
        rows.add(Row.info("Supply chest: " + Backend.pos(cfg.get(), "supply")));
        rows.add(Row.action("Save the supply chest I am looking at", "Set chest", () -> Backend.command("survival supply set")));
        rows.add(Row.action("Restock right now", "Restock now", () -> Backend.command("survival restock now")));

        JsonObject want = cfg.get().has("want") && cfg.get().get("want").isJsonObject() ? cfg.get().getAsJsonObject("want") : new JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : want.entrySet()) {
            String item = e.getKey();
            int count = e.getValue().getAsInt();
            rows.add(Row.removable("Carry: " + item, () -> String.valueOf(count),
                    text -> {
                        int n = Integer.parseInt(text);
                        if (n < 1 || n > 2304) throw new NumberFormatException();
                        Backend.command("survival want " + Backend.quote(item) + " " + n);
                    },
                    () -> Backend.command("survival want remove " + Backend.quote(item))));
        }
        rows.add(Row.addRule("Add to want-list (item, count)", "16",
                (item, n) -> Backend.command("survival want " + Backend.quote(item) + " " + Math.max(1, n))));
        return rows;
    }

    private static Row toggle(Supplier<JsonObject> cfg, String label, String jsonKey, String command) {
        return Row.bool(label, () -> Backend.bool(cfg.get(), jsonKey),
                v -> Backend.command("survival " + command + " " + (v ? "on" : "off")));
    }
}
