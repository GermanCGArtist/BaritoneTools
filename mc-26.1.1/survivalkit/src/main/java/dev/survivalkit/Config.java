package dev.survivalkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted settings (config/survivalkit.json). */
final class Config {
    boolean eat = true;
    boolean defence = true;
    boolean sleep = true;
    boolean restock = true;

    /** Use a golden apple when health is low and the food bar is already full (food alone cannot heal then). */
    boolean golden = true;

    /** Keep running when the game window loses focus (second monitor): turns off vanilla's pause-on-lost-focus. */
    boolean background = true;

    /** Eat when the food level (0-20) is at or below this. */
    int eatBelow = 14;

    int[] bed = null;
    int[] supply = null;

    /** Restock rules: item id fragment (or @food) -> how many to carry. */
    Map<String, Integer> want = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("survivalkit.json");
    }

    static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) cfg = c;
                }
            } else {
                cfg.want.put("@food", 16);
                save();
            }
            if (cfg.want == null) cfg.want = new LinkedHashMap<>();
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
