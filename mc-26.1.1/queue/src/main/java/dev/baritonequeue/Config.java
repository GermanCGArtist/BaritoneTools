package dev.baritonequeue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted settings (config/baritonequeue.json). */
final class Config {
    /** The commands as typed, one per line. */
    List<String> script = new ArrayList<>();

    /** Saved snippets: name -> lines. Inserted into the script from the editor. */
    java.util.Map<String, List<String>> presets = defaultPresets();

    private static java.util.Map<String, List<String>> defaultPresets() {
        java.util.Map<String, List<String>> p = new java.util.LinkedHashMap<>();
        p.put("Clear trees", new ArrayList<>(List.of(
                "// Clear trees: logs, then leaves (select the area first)",
                "sel replace oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log air",
                "sel replace oak_leaves birch_leaves spruce_leaves jungle_leaves acacia_leaves dark_oak_leaves mangrove_leaves cherry_leaves pale_oak_leaves azalea_leaves flowering_azalea_leaves air")));
        p.put("Replace water and lava", new ArrayList<>(List.of(
                "// Plug water and lava with cobblestone (change the last word to use another block)",
                "sel replace water lava cobblestone")));
        p.put("Mine area", new ArrayList<>(List.of(
                "// Mine out the selection",
                "sel ca")));
        p.put("Profile: AFK mining", new ArrayList<>(List.of(
                "// Switch SurvivalKit and BuildTools to a careful setup for long unattended mining",
                "/survival eat on",
                "/survival defence on",
                "/survival sleep on",
                "/survival restock on",
                "/survival resume on",
                "timeout 1800")));
        p.put("Profile: building", new ArrayList<>(List.of(
                "// Only eat and restock while building; no sleeping, no fighting",
                "/survival eat on",
                "/survival restock on",
                "/survival defence off",
                "/survival sleep off")));
        p.put("Wait for night, then sleep", new ArrayList<>(List.of(
                "// Example of a condition: do something only when it applies",
                "wait until night",
                "if not raining",
                "  // the bot sleeps by itself; this just waits for the morning",
                "  wait until day",
                "end")));
        return p;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritonequeue.json");
    }

    static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) cfg = c;
                    if (cfg.script == null) cfg.script = new ArrayList<>();
                    if (cfg.presets == null) cfg.presets = defaultPresets();
                }
            } else {
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(cfg, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
