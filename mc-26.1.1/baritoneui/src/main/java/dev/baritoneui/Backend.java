package dev.baritoneui;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything the screen talks to. Baritone is read and written directly; LoadChest and SurvivalKit are controlled
 * through their own chat commands (no hard dependency on them) and read back from their saved config files.
 */
final class Backend {
    private Backend() {}

    // ------------------------------------------------------------------ Baritone

    static Settings settings() {
        return BaritoneAPI.getSettings();
    }

    /** Sets a Baritone setting from text (the same parsing as `#set <name> <value>`) and saves it. */
    static void applySetting(String name, String value) {
        SettingsUtil.parseAndApply(settings(), name, value);
        SettingsUtil.save(settings());
    }

    static void stopEverything() {
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
    }

    // ------------------------------------------------------------------ addon mods

    static boolean loaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    /** Runs a client command such as "loadchest auto on" (the leading slash is not needed). */
    static void command(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(command);
    }

    /** A value in quotes, so names like @food survive command parsing. */
    static String quote(String s) {
        return "\"" + s.replace("\"", "") + "\"";
    }

    /** The addon's saved config (it saves right after every command, so this is always current). */
    static JsonObject json(String file) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(file);
        if (!Files.exists(path)) return new JsonObject();
        try (Reader r = Files.newBufferedReader(path)) {
            var parsed = JsonParser.parseReader(r);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    static int num(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    static String pos(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray() || o.getAsJsonArray(key).size() < 3) return "not set";
        var a = o.getAsJsonArray(key);
        return a.get(0).getAsInt() + ", " + a.get(1).getAsInt() + ", " + a.get(2).getAsInt();
    }
}
