package dev.baritonequeue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Completions for the line being typed, straight from Baritone's own tab completion (so they match its commands). */
final class Suggest {
    private Suggest() {}

    private static final int MAX = 8;

    /**
     * @param before the text of the current line up to the cursor
     * @return the possible replacements for the last word of `before`, best first
     */
    static List<String> complete(String before) {
        String line = before.stripLeading();
        if (line.isEmpty() || line.startsWith("/") || Util.commentStart(line) >= 0) return List.of();
        String prefix = baritone.api.BaritoneAPI.getSettings().prefix.value;
        if (line.startsWith(prefix)) line = line.substring(prefix.length());
        String lower = line.toLowerCase();
        List<String> special = special(lower);
        if (special != null) return special;
        if (lower.equals("wait") || lower.startsWith("wait ")) return List.of();
        try {
            List<String> out = Util.baritone().getCommandManager().tabComplete(line)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .limit(MAX)
                    .collect(Collectors.toList());
            // nothing to add when the word is already complete
            String partial = lastWord(line);
            if (out.size() == 1 && out.get(0).equalsIgnoreCase(partial)) return List.of();
            return new ArrayList<>(out);
        } catch (Throwable t) {
            return List.of();   // odd half-typed input: no suggestions rather than an error
        }
    }

    private static final List<String> KEYWORDS = List.of("repeat", "if", "else", "end", "timeout", "wait until");

    /** Suggestions for our own keywords and their arguments, or null when Baritone's completion applies. */
    private static List<String> special(String lower) {
        String partial = lastWord(lower);
        if (lower.equals("wait ")) return List.of("until");
        if (lower.startsWith("wait until ")) return startingWith(List.of("day", "night", "clear"), partial);
        if (lower.startsWith("if ")) {
            if (lower.contains(" below ") || lower.contains(" above ")) return List.of();
            if (lower.matches(".*\\b(health|food) $")) return List.of("below", "above");
            return startingWith(Script.CONDITIONS, partial);
        }
        if (!lower.contains(" ")) {
            List<String> own = startingWith(KEYWORDS, lower);
            if (!own.isEmpty() && !(own.size() == 1 && own.get(0).equals(lower))) {
                List<String> all = new ArrayList<>(own);
                try {
                    Util.baritone().getCommandManager().tabComplete(lower)
                            .filter(s -> s != null && !s.isBlank()).distinct().limit(MAX - own.size()).forEach(all::add);
                } catch (Throwable ignored) {
                }
                return all;
            }
        }
        return null;
    }

    private static List<String> startingWith(List<String> options, String partial) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(partial) && !o.equals(partial)) out.add(o);
        }
        return out;
    }

    /** The word the cursor is in (empty after a space). */
    static String lastWord(String text) {
        int i = text.length();
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return text.substring(i);
    }
}
