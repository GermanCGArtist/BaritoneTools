package dev.baritonequeue;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Colours for the editor, one per character, like a code editor: commands, blocks, numbers, comments, mistakes. */
final class Syntax {
    private Syntax() {}

    static final int DEFAULT = 0xFFD4D4D4;
    static final int COMMENT = 0xFF6A9955;    // green
    static final int COMMAND = 0xFF569CD6;    // blue
    static final int SUB = 0xFFC586C0;        // purple: sel replace, sel ca, ...
    static final int BLOCK = 0xFF4EC9B0;      // teal
    static final int NUMBER = 0xFFB5CEA8;     // light green
    static final int KEYWORD = 0xFFDCDCAA;    // yellow: wait
    static final int CHAT = 0xFFCE9178;       // orange: /command
    static final int ERROR = 0xFFF44747;      // red: unknown command

    private static final Set<String> SEL = Set.of("sel", "selection", "s");
    private static final Map<String, Boolean> blockCache = new HashMap<>();

    static int[] colors(String value) {
        int[] c = new int[value.length()];
        Arrays.fill(c, DEFAULT);
        int pos = 0;
        while (pos <= value.length()) {
            int end = value.indexOf('\n', pos);
            if (end < 0) end = value.length();
            colorLine(value, pos, end, c);
            pos = end + 1;
        }
        return c;
    }

    private static void colorLine(String v, int s, int e, int[] c) {
        int ci = Util.commentStart(v.substring(s, e));
        int codeEnd = ci >= 0 ? s + ci : e;
        for (int i = codeEnd; i < e; i++) c[i] = COMMENT;

        int i = s, index = 0;
        String label = "";
        while (i < codeEnd) {
            while (i < codeEnd && Character.isWhitespace(v.charAt(i))) i++;
            if (i >= codeEnd) break;
            int ts = i;
            while (i < codeEnd && !Character.isWhitespace(v.charAt(i))) i++;
            String tok = v.substring(ts, i);
            int color;
            if (index == 0) {
                if (tok.startsWith("/")) {
                    label = "/";
                    color = CHAT;
                } else {
                    String prefix = baritone.api.BaritoneAPI.getSettings().prefix.value;
                    label = (tok.startsWith(prefix) ? tok.substring(prefix.length()) : tok).toLowerCase();
                    if (label.equals("wait") || Script.CONTROL.contains(label)) color = KEYWORD;
                    else color = known(label) ? COMMAND : ERROR;
                }
            } else if (label.equals("/")) {
                color = CHAT;
            } else if (Script.CONTROL.contains(label) || label.equals("wait")) {
                color = tok.matches("-?\\d+(\\.\\d+)?|\\d{1,2}:\\d{2}") ? NUMBER
                        : (Script.CONDITIONS.contains(tok.toLowerCase()) || Set.of("below", "above", "until", "day", "night", "clear", "off").contains(tok.toLowerCase())) ? SUB
                        : isBlock(tok) ? BLOCK : DEFAULT;
            } else if (tok.matches("-?\\d+(\\.\\d+)?")) {
                color = NUMBER;
            } else if (isBlock(tok)) {
                color = BLOCK;
            } else if (index == 1 && SEL.contains(label)) {
                color = SUB;
            } else {
                color = DEFAULT;
            }
            for (int k = ts; k < i; k++) c[k] = color;
            index++;
        }
    }

    private static boolean known(String label) {
        try {
            return Util.baritone().getCommandManager().getCommand(label) != null;
        } catch (Throwable t) {
            return true;   // no judgement when Baritone can not be asked
        }
    }

    static boolean isBlock(String word) {
        String w = word.toLowerCase();
        int bracket = w.indexOf('[');
        if (bracket >= 0) w = w.substring(0, bracket);
        if (w.isEmpty()) return false;
        Boolean cached = blockCache.get(w);
        if (cached != null) return cached;
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        boolean ok = id != null && BuiltInRegistries.BLOCK.containsKey(id);
        blockCache.put(w, ok);
        return ok;
    }
}
