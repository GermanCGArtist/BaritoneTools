package dev.baritonequeue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The script language on top of Baritone's commands:
 *   repeat 3 ... end        run the lines between 3 times
 *   if <condition> ... [else ...] end
 *   wait until day|night|clear|HH:MM
 *   timeout 300             give up on a step after 300 s and go on (timeout off clears it)
 *   wait 30, /command, // comment
 * Conditions: full, night, day, raining, thundering, has <item> [n], health|food below|above <n>, each with "not".
 */
final class Script {
    private Script() {}

    record Step(int line, String command) {}

    /** A script ready to run: repeats unrolled, if/else/end linked. */
    static final class Parsed {
        List<Step> steps = new ArrayList<>();
        int[] jump = new int[0];            // if -> its else or end, else -> its end (indexes into steps)
        int[] actionsBefore = new int[1];   // number of real steps (not if/else/end/timeout) before index i
        String error;
    }

    static final Set<String> CONTROL = Set.of("repeat", "if", "else", "end", "timeout");
    static final List<String> CONDITIONS = List.of("not", "full", "night", "day", "raining", "thundering", "has", "health", "food");
    private static final int MAX_STEPS = 5000;

    static String first(String command) {
        int sp = command.indexOf(' ');
        return (sp < 0 ? command : command.substring(0, sp)).toLowerCase();
    }

    static boolean isControl(String command) {
        return CONTROL.contains(first(command));
    }

    /** The lines that count: no blanks, no comments, no leading #. */
    static List<Step> lines(List<String> script) {
        List<Step> out = new ArrayList<>();
        String prefix = baritone.api.BaritoneAPI.getSettings().prefix.value;
        for (int i = 0; i < script.size(); i++) {
            String c = Util.stripComment(script.get(i)).trim();
            if (c.isEmpty()) continue;
            if (c.startsWith(prefix)) c = c.substring(prefix.length()).trim();
            if (!c.isEmpty()) out.add(new Step(i, c));
        }
        return out;
    }

    static Parsed parse(List<String> script) {
        Parsed p = new Parsed();
        List<Step> in = lines(script);
        p.error = expand(in, 0, in.size(), p.steps);
        if (p.error != null) return p;
        int n = p.steps.size();
        p.jump = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            String w = first(p.steps.get(i).command());
            switch (w) {
                case "if" -> stack.push(i);
                case "else" -> {
                    if (stack.isEmpty() || !first(p.steps.get(stack.peek()).command()).equals("if") || p.jump[stack.peek()] != 0) {
                        p.error = "Line " + (p.steps.get(i).line() + 1) + ": 'else' without a matching 'if'.";
                        return p;
                    }
                    p.jump[stack.peek()] = i;
                    stack.pop();
                    stack.push(i);
                }
                case "end" -> {
                    if (stack.isEmpty()) {
                        p.error = "Line " + (p.steps.get(i).line() + 1) + ": 'end' without a matching 'if' or 'repeat'.";
                        return p;
                    }
                    p.jump[stack.pop()] = i;   // an "if" without else, or the "else": both end here
                }
                default -> { }
            }
        }
        if (!stack.isEmpty()) {
            p.error = "Line " + (p.steps.get(stack.peek()).line() + 1) + ": 'if' is never closed with 'end'.";
            return p;
        }
        p.actionsBefore = new int[n + 1];
        for (int i = 0; i < n; i++) {
            p.actionsBefore[i + 1] = p.actionsBefore[i] + (isControl(p.steps.get(i).command()) ? 0 : 1);
        }
        return p;
    }

    /** Copies in[from..to) to out with every repeat block unrolled. Returns an error message or null. */
    private static String expand(List<Step> in, int from, int to, List<Step> out) {
        for (int i = from; i < to; i++) {
            Step s = in.get(i);
            String c = s.command();
            if (first(c).equals("repeat")) {
                String[] w = c.split("\\s+");
                int times;
                try {
                    times = Integer.parseInt(w.length > 1 ? w[1] : "");
                    if (times < 1 || times > 1000) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    return "Line " + (s.line() + 1) + ": 'repeat' needs a number from 1 to 1000, for example: repeat 3";
                }
                int depth = 1, j = i + 1;
                for (; j < to; j++) {
                    String f = first(in.get(j).command());
                    if (f.equals("repeat") || f.equals("if")) depth++;
                    else if (f.equals("end") && --depth == 0) break;
                }
                if (j >= to) return "Line " + (s.line() + 1) + ": 'repeat' is never closed with 'end'.";
                for (int r = 0; r < times; r++) {
                    String err = expand(in, i + 1, j, out);
                    if (err != null) return err;
                    if (out.size() > MAX_STEPS) return "Line " + (s.line() + 1) + ": the repeats make more than " + MAX_STEPS + " steps.";
                }
                i = j;
            } else {
                out.add(s);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ conditions

    private static long clock() {
        Level level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getOverworldClockTime() % 24000L;
    }

    static boolean isNight() {
        long t = clock();
        return t >= 12542 && t < 23460;
    }

    /** null when the condition reads correctly, else what is wrong. */
    static String checkCondition(String expr) {
        String[] w = expr.trim().toLowerCase().split("\\s+");
        int i = 0;
        if (i < w.length && w[i].equals("not")) i++;
        if (i >= w.length || w[i].isEmpty()) return "'if' needs a condition: " + String.join(", ", CONDITIONS);
        String c = w[i];
        int rest = w.length - i - 1;
        switch (c) {
            case "full", "night", "day", "raining", "thundering" -> {
                return rest == 0 ? null : "'" + c + "' takes nothing after it.";
            }
            case "has" -> {
                if (rest < 1 || rest > 2) return "Use: has <item> [count]";
                if (rest == 2 && !w[i + 2].matches("\\d+")) return "'" + w[i + 2] + "' is not a count.";
                return null;
            }
            case "health", "food" -> {
                if (rest != 2 || !(w[i + 1].equals("below") || w[i + 1].equals("above")) || !w[i + 2].matches("\\d+"))
                    return "Use: " + c + " below|above <number>";
                return null;
            }
            default -> {
                return "Unknown condition '" + c + "'. Conditions: " + String.join(", ", CONDITIONS);
            }
        }
    }

    static boolean eval(String expr) {
        String[] w = expr.trim().toLowerCase().split("\\s+");
        int i = 0;
        boolean not = false;
        if (w[i].equals("not")) { not = true; i++; }
        boolean r = evalPlain(w, i);
        return not != r;
    }

    private static boolean evalPlain(String[] w, int i) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) return false;
        switch (w[i]) {
            case "full" -> {
                for (int k = 0; k < 36; k++) if (p.getInventory().getItem(k).isEmpty()) return false;
                return true;
            }
            case "night" -> { return level.dimension() == Level.OVERWORLD && isNight(); }
            case "day" -> { return level.dimension() != Level.OVERWORLD || !isNight(); }
            case "raining" -> { return level.isRaining(); }
            case "thundering" -> { return level.isThundering(); }
            case "has" -> {
                String item = w[i + 1];
                int need = w.length > i + 2 ? Integer.parseInt(w[i + 2]) : 1;
                int have = 0;
                for (int k = 0; k < 36; k++) {
                    ItemStack st = p.getInventory().getItem(k);
                    if (st.isEmpty()) continue;
                    var id = BuiltInRegistries.ITEM.getKey(st.getItem());
                    if (item.contains(":") ? id.toString().equals(item) : id.getPath().contains(item)) have += st.getCount();
                }
                return have >= need;
            }
            case "health", "food" -> {
                double value = w[i].equals("health") ? p.getHealth() : p.getFoodData().getFoodLevel();
                int n = Integer.parseInt(w[i + 2]);
                return w[i + 1].equals("below") ? value < n : value > n;
            }
            default -> { return false; }
        }
    }

    // ------------------------------------------------------------------ wait until

    static String checkUntil(String what) {
        String w = what.trim().toLowerCase();
        if (w.equals("day") || w.equals("night") || w.equals("clear")) return null;
        if (w.matches("\\d{1,2}:\\d{2}")) {
            String[] t = w.split(":");
            if (Integer.parseInt(t[0]) < 24 && Integer.parseInt(t[1]) < 60) return null;
        }
        return "'wait until' takes day, night, clear (no rain) or a time like 06:30.";
    }

    static boolean reached(String what) {
        String w = what.trim().toLowerCase();
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        return switch (w) {
            case "day" -> level.dimension() != Level.OVERWORLD || !isNight();
            case "night" -> level.dimension() == Level.OVERWORLD && isNight();
            case "clear" -> !level.isRaining() && !level.isThundering();
            default -> {
                String[] t = w.split(":");
                LocalTime now = LocalTime.now();
                yield now.getHour() == Integer.parseInt(t[0]) && now.getMinute() == Integer.parseInt(t[1]);
            }
        };
    }
}
