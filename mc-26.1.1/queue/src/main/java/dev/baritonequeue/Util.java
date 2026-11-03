package dev.baritonequeue;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

final class Util {
    private Util() {}

    static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Queue] " + s));
    }

    /** Where a // comment starts in the line (at the start or after a space), or -1. */
    static int commentStart(String line) {
        int i = line.indexOf("//");
        while (i >= 0) {
            if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) return i;
            i = line.indexOf("//", i + 2);
        }
        return -1;
    }

    /** The line without its // comment. */
    static String stripComment(String line) {
        int i = commentStart(line);
        return i < 0 ? line : line.substring(0, i);
    }

    private static final Map<String, Long> lastWarn = new HashMap<>();

    /** Tells the player about a problem in chat, at most once a minute per kind. */
    static void warn(String key, String text) {
        long t = System.currentTimeMillis();
        Long last = lastWarn.get(key);
        if (last != null && t - last < 60_000) return;
        lastWarn.put(key, t);
        msg("PROBLEM: " + text);
    }

    /** True while any Baritone task runs (#mine, #farm, #build, #sel ca, #goto, ...). */
    static boolean baritoneBusy() {
        IBaritone b = baritone();
        return b.getMineProcess().isActive() || b.getBuilderProcess().isActive() || b.getFarmProcess().isActive()
                || b.getExploreProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getFollowProcess().isActive() || b.getCustomGoalProcess().isActive();
    }
}
