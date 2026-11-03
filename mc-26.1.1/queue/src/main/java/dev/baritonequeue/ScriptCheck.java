package dev.baritonequeue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Looks through the script without running anything: unknown commands, wrong block names, a selection that is
 * missing, lines that never finish. It cannot know whether the world lets a command succeed, only whether the
 * script is in order.
 */
final class ScriptCheck {
    private ScriptCheck() {}

    enum Level { OK, WARN, ERROR }

    record Entry(Level level, int line, String text) {}

    /** Commands that run until you stop them. */
    private static final Set<String> ENDLESS = Set.of("farm", "follow", "explore", "explorefilter");
    private static final Set<String> SEL = Set.of("sel", "selection", "s");
    /** sel subcommands that need a selection to exist. */
    private static final Set<String> SEL_NEEDS = Set.of("set", "fill", "s", "f", "walls", "w", "shell", "sphere", "hsphere",
            "cylinder", "hcylinder", "cleararea", "ca", "replace", "r", "copy", "cp", "expand", "contract", "shift");
    /** sel subcommands whose words are blocks. */
    private static final Set<String> SEL_BLOCKS = Set.of("set", "fill", "s", "f", "walls", "w", "shell", "sphere", "hsphere",
            "cylinder", "hcylinder", "replace", "r");

    static List<Entry> run(List<String> script) {
        List<Entry> out = new ArrayList<>();
        var b = Util.baritone();
        boolean haveSel = b.getSelectionManager().getSelections().length > 0;
        boolean pos1 = false, pos2 = false;
        int checked = 0, errors = 0, warnings = 0;
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();   // {line, 0 repeat | 1 if | 2 else}
        String prefix = baritone.api.BaritoneAPI.getSettings().prefix.value;

        List<String> lines = new ArrayList<>();
        for (String raw : script) lines.add(raw);
        for (int i = 0; i < lines.size(); i++) {
            String c = Util.stripComment(lines.get(i)).trim();
            if (c.isEmpty()) continue;
            int n = i + 1;
            checked++;
            if (c.startsWith(prefix)) c = c.substring(prefix.length()).trim();
            String[] w = c.split("\\s+");
            String label = w[0].toLowerCase();

            if (Script.CONTROL.contains(label)) {
                String problem = null;
                switch (label) {
                    case "repeat" -> {
                        try {
                            int t = Integer.parseInt(w.length > 1 ? w[1] : "");
                            if (t < 1 || t > 1000) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            problem = "'repeat' needs a number from 1 to 1000, for example: repeat 3";
                        }
                        open.push(new int[]{n, 0});
                    }
                    case "if" -> {
                        problem = Script.checkCondition(c.length() > 2 ? c.substring(2).trim() : "");
                        open.push(new int[]{n, 1});
                    }
                    case "else" -> {
                        if (open.isEmpty() || open.peek()[1] != 1) problem = "'else' without a matching 'if'.";
                        else { open.pop(); open.push(new int[]{n, 2}); }
                    }
                    case "end" -> {
                        if (open.isEmpty()) problem = "'end' without a matching 'if' or 'repeat'.";
                        else open.pop();
                    }
                    default -> {   // timeout
                        try {
                            if (w.length < 2 || !(w[1].equals("off") || Double.parseDouble(w[1]) > 0)) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            problem = "'timeout' needs a number of seconds or 'off', for example: timeout 300";
                        }
                    }
                }
                if (problem != null) {
                    out.add(new Entry(Level.ERROR, n, problem));
                    errors++;
                }
                continue;
            }
            if (label.equals("wait") && w.length > 1 && w[1].equalsIgnoreCase("until")) {
                String err = Script.checkUntil(c.length() > 11 ? c.substring(11).trim() : "");
                if (err != null) {
                    out.add(new Entry(Level.ERROR, n, err));
                    errors++;
                }
                continue;
            }
            if (label.equals("wait")) {
                try {
                    if (w.length < 2 || Double.parseDouble(w[1]) <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    out.add(new Entry(Level.ERROR, n, "'wait' needs a number of seconds, for example: wait 30"));
                    errors++;
                }
                continue;
            }
            if (c.startsWith("/")) {
                out.add(new Entry(Level.WARN, n, "'" + c + "' is a chat command: it is run, but not checked."));
                warnings++;
                continue;
            }

            var command = b.getCommandManager().getCommand(label);
            if (command == null) {
                String hint = "";
                try {
                    var maybe = b.getCommandManager().tabComplete(label).limit(3).toList();
                    if (!maybe.isEmpty()) hint = " Did you mean: " + String.join(", ", maybe) + "?";
                } catch (Throwable ignored) {
                }
                out.add(new Entry(Level.ERROR, n, "Unknown Baritone command '" + w[0] + "'." + hint));
                errors++;
                continue;
            }

            if (SEL.contains(label) && w.length > 1) {
                String sub = w[1].toLowerCase();
                switch (sub) {
                    case "pos1", "1" -> pos1 = true;
                    case "pos2", "2" -> pos2 = true;
                    case "clear", "reset" -> { haveSel = false; pos1 = false; pos2 = false; }
                    default -> {}
                }
                if (pos1 && pos2) haveSel = true;
                if (SEL_NEEDS.contains(sub) && !haveSel) {
                    out.add(new Entry(Level.ERROR, n, "'" + c + "' needs a selection. Set it first with #sel pos1 and #sel pos2 (in the game, or as lines above this one)."));
                    errors++;
                }
                if (SEL_BLOCKS.contains(sub)) {
                    for (int k = 2; k < w.length; k++) {
                        String bad = badBlock(w[k]);
                        if (bad != null) {
                            out.add(new Entry(Level.ERROR, n, "'" + bad + "' is not a block name."));
                            errors++;
                        }
                    }
                }
            } else if (label.equals("mine") || label.equals("m")) {
                boolean finite = false;
                for (int k = 1; k < w.length; k++) {
                    if (w[k].matches("\\d+")) { finite = true; continue; }
                    String bad = badBlock(w[k]);
                    if (bad != null) {
                        out.add(new Entry(Level.ERROR, n, "'" + bad + "' is not a block name."));
                        errors++;
                    }
                }
                if (w.length == 1) {
                    out.add(new Entry(Level.ERROR, n, "'mine' needs a block, for example: mine diamond_ore"));
                    errors++;
                } else if (!finite && i < lastIndex(lines)) {
                    out.add(new Entry(Level.WARN, n, "'mine' without a number never finishes, so the lines below will not start by themselves (use Skip step). Example: mine 64 iron_ore"));
                    warnings++;
                }
            } else if (ENDLESS.contains(label) && i < lastIndex(lines)) {
                out.add(new Entry(Level.WARN, n, "'" + label + "' never finishes by itself, so the lines below will not start (use Skip step)."));
                warnings++;
            }
        }

        for (int[] o : open) {
            out.add(new Entry(Level.ERROR, o[0], (o[1] == 0 ? "'repeat'" : "'if'") + " is never closed with 'end'."));
            errors++;
        }
        if (errors == 0) {
            String err = Script.parse(script).error;
            if (err != null) {
                out.add(new Entry(Level.ERROR, 0, err));
                errors++;
            }
        }

        String summary = checked + " line" + (checked == 1 ? "" : "s") + " checked: "
                + (errors == 0 ? "no errors" : errors + " error" + (errors == 1 ? "" : "s"))
                + (warnings > 0 ? ", " + warnings + " warning" + (warnings == 1 ? "" : "s") : "") + ".";
        out.add(new Entry(errors == 0 ? (warnings == 0 ? Level.OK : Level.WARN) : Level.ERROR, 0, summary));
        return out;
    }

    private static int lastIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String c = Util.stripComment(lines.get(i)).trim();
            if (!c.isEmpty()) return i;
        }
        return -1;
    }

    /** null when the word is a block (or not something to check); otherwise the word. */
    private static String badBlock(String word) {
        String w = word.toLowerCase();
        int bracket = w.indexOf('[');
        if (bracket >= 0) w = w.substring(0, bracket);
        if (w.isEmpty() || w.matches("-?\\d+")) return null;
        // words that are not blocks at all
        if (Set.of("all", "up", "down", "north", "south", "east", "west", "a", "d").contains(w)) return null;
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? null : word;
    }
}
