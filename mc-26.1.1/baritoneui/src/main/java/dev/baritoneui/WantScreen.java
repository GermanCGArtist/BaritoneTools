package dev.baritoneui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Editor for SurvivalKit's work items, one per line:
 *   minecraft:diamond_pickaxe 2 efficiency 5;mending
 *   food 16
 * item, how many to carry, and optionally enchantments (name [level], separated by ;). Colours show what is
 * understood, Tab completes items and enchantments, Test checks every line, Save writes the list to SurvivalKit.
 */
final class WantScreen extends Screen {
    private record Chip(int x, int y, int w, int index) {}

    private record Problem(int line, String text) {}

    private static final int GLFW_KEY_TAB = 258;
    private static final int PANEL_H = 44;
    private static final int GUTTER = 6;
    private static final int INNER = 4;

    private static final int DEFAULT = 0xFFD4D4D4, ITEM = 0xFF4EC9B0, COUNT = 0xFFB5CEA8, ENCH = 0xFFC586C0,
            BAD = 0xFFF44747, OK = 0xFF60E060;

    private static List<String> itemIds;

    private MultiLineEditBox box;
    private MultilineTextField field;
    private int left, width0, boxY, boxH, panelY;
    private List<String> suggestions = List.of();
    private final List<Chip> chips = new ArrayList<>();
    private String lastKey = "";
    private List<Problem> results;
    private String notice = "";
    private long noticeUntil;
    private String colorText;
    private int[] colors = new int[0];
    private final Screen parent;

    WantScreen(Screen parent) {
        super(Component.literal("Work items"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ data

    private static List<String> items() {
        if (itemIds == null) {
            List<String> out = new ArrayList<>();
            for (Identifier id : BuiltInRegistries.ITEM.keySet()) out.add(id.toString());
            out.sort(String::compareTo);
            itemIds = out;
        }
        return itemIds;
    }

    private static Set<String> enchants() {
        Set<String> out = new HashSet<>();
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            level.registryAccess().lookup(Registries.ENCHANTMENT).ifPresent(reg ->
                    reg.listElementIds().forEach(k -> out.add(k.identifier().getPath())));
        }
        return out;
    }

    private static String initialText() {
        JsonObject want = Backend.json("survivalkit.json");
        StringBuilder sb = new StringBuilder();
        if (want.has("want") && want.get("want").isJsonObject()) {
            for (var e : want.getAsJsonObject("want").entrySet()) {
                String key = e.getKey();
                int sp = key.indexOf(' ');
                String item = sp < 0 ? key : key.substring(0, sp);
                String rest = sp < 0 ? "" : key.substring(sp + 1).replace(':', ' ');
                sb.append(item).append(' ').append(e.getValue().getAsInt());
                if (!rest.isEmpty()) sb.append(' ').append(rest);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean knownItem(String item) {
        String l = item.toLowerCase();
        if (l.equals("@food") || l.equals("food")) return true;
        if (l.contains(":")) {
            Identifier id = Identifier.tryParse(l);
            return id != null && BuiltInRegistries.ITEM.containsKey(id);
        }
        for (String id : items()) {
            if (id.substring(id.indexOf(':') + 1).contains(l)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ init

    @Override
    protected void init() {
        width0 = Math.min(width - 24, 460);
        left = (width - width0) / 2;
        boxY = 52;
        boxH = Math.max(60, height - boxY - PANEL_H - 4 - 16 - 26 - 6);
        panelY = boxY + boxH + 4;

        box = MultiLineEditBox.builder()
                .setX(left + GUTTER)
                .setY(boxY)
                .setTextColor(0x04FFFFFF)
                .setTextShadow(false)
                .setPlaceholder(Component.literal("One item per line, for example:\nfood 16\nminecraft:diamond_pickaxe 2 efficiency 5;mending"))
                .build(font, width0 - GUTTER, boxH, Component.literal("Work items"));
        box.setCharacterLimit(6000);
        box.setValue(initialText());
        box.setValueListener(text -> results = null);
        addRenderableWidget(box);
        setFocused(box);
        field = textFieldOf(box);

        int by = panelY + PANEL_H + 16;
        int gap = 4, bw = (width0 - 2 * gap) / 3;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(left, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> results = check()).bounds(left + bw + gap, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + 2 * (bw + gap), by, bw, 20).build());
    }

    private static MultilineTextField textFieldOf(MultiLineEditBox box) {
        try {
            var f = MultiLineEditBox.class.getDeclaredField("textField");
            f.setAccessible(true);
            return (MultilineTextField) f.get(box);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static java.lang.reflect.Method viewBegin, viewEnd;

    private static int[] range(Object view) {
        try {
            if (viewBegin == null) {
                Class<?> c = Class.forName("net.minecraft.client.gui.components.MultilineTextField$StringView");
                viewBegin = c.getDeclaredMethod("beginIndex");
                viewEnd = c.getDeclaredMethod("endIndex");
                viewBegin.setAccessible(true);
                viewEnd.setAccessible(true);
            }
            return new int[]{(int) viewBegin.invoke(view), (int) viewEnd.invoke(view)};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private void say(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 5000;
    }

    // ------------------------------------------------------------------ check and save

    private List<Problem> check() {
        List<Problem> out = new ArrayList<>();
        Set<String> known = enchants();
        String[] lines = box.getValue().split("\n", -1);
        int count = 0, errors = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isEmpty()) continue;
            count++;
            String err = lineError(l, known);
            if (err != null) {
                out.add(new Problem(i + 1, err));
                errors++;
            }
        }
        out.add(new Problem(0, count + " item" + (count == 1 ? "" : "s") + " checked: " + (errors == 0 ? "no errors." : errors + " error" + (errors == 1 ? "" : "s") + ".")));
        return out;
    }

    private static String lineError(String line, Set<String> known) {
        String[] t = line.split("\\s+", 3);
        if (t.length < 2) return "Needs an item and a count, for example: food 16";
        if (!knownItem(t[0])) return "No item matches '" + t[0] + "'.";
        try {
            int n = Integer.parseInt(t[1]);
            if (n < 1 || n > 2304) return "The count must be between 1 and 2304.";
        } catch (NumberFormatException e) {
            return "'" + t[1] + "' is not a count.";
        }
        if (t.length == 3 && !known.isEmpty()) {
            for (String part : t[2].split(";")) {
                String[] w = part.trim().toLowerCase().split("[\\s:]+");
                if (w.length == 0 || w[0].isEmpty()) continue;
                String name = w[0].startsWith("minecraft:") ? w[0].substring(10) : w[0];
                if (!known.contains(name)) return "Unknown enchantment '" + w[0] + "'.";
                if (w.length > 1 && !w[1].matches("\\d+")) return "'" + w[1] + "' is not an enchantment level.";
            }
        }
        return null;
    }

    private void save() {
        List<Problem> problems = check();
        int errors = (int) problems.stream().filter(p -> p.line() > 0).count();
        if (errors > 0) {
            results = problems;
            say("Fix the marked lines first - nothing was saved.");
            return;
        }
        Backend.command("survival workitem clear");
        int n = 0;
        for (String raw : box.getValue().split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            Backend.command("survival workitem " + l);
            n++;
        }
        say("Saved " + n + " work item" + (n == 1 ? "" : "s") + " to SurvivalKit.");
    }

    // ------------------------------------------------------------------ completion

    @Override
    public void tick() {
        super.tick();
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        String key = cursor + ":" + value;
        if (key.equals(lastKey)) return;
        lastKey = key;
        String before = value.substring(0, cursor);
        suggestions = complete(before.substring(before.lastIndexOf('\n') + 1));
    }

    private static String partialOf(String line) {
        int i = line.length();
        while (i > 0 && !Character.isWhitespace(line.charAt(i - 1)) && line.charAt(i - 1) != ';') i--;
        return line.substring(i);
    }

    private List<String> complete(String line) {
        String trimmed = line.stripLeading();
        String partial = partialOf(trimmed).toLowerCase();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length - (Character.isWhitespace(trimmed.charAt(trimmed.length() - 1)) ? 0 : 1);
        List<String> out = new ArrayList<>();
        if (words == 0) {
            if (partial.isEmpty()) return List.of("food");
            if ("food".startsWith(partial)) out.add("food");
            List<String> startsWith = new ArrayList<>(), contains = new ArrayList<>();
            for (String id : items()) {
                String path = id.substring(id.indexOf(':') + 1);
                if (path.startsWith(partial) || id.startsWith(partial)) startsWith.add(id);
                else if (path.contains(partial)) contains.add(id);
            }
            out.addAll(startsWith);
            out.addAll(contains);
        } else if (words >= 2) {
            // after the count: enchantment names (not after a name that still waits for its level)
            for (String e : enchants().stream().sorted().toList()) {
                if (e.startsWith(partial) && !e.equals(partial)) out.add(e);
            }
        }
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    private void accept(String suggestion) {
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        String before = value.substring(0, cursor);
        String line = before.substring(before.lastIndexOf('\n') + 1);
        int start = cursor - partialOf(line).length();
        String insert = suggestion + " ";
        box.setValue(value.substring(0, start) + insert + value.substring(cursor));
        field.seekCursor(Whence.ABSOLUTE, start + insert.length());
        lastKey = "";
        setFocused(box);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() == box && event.key() == GLFW_KEY_TAB && !suggestions.isEmpty()) {
            accept(suggestions.get(0));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (results == null) {
            for (Chip c : chips) {
                if (event.x() >= c.x() && event.x() < c.x() + c.w() && event.y() >= c.y() && event.y() < c.y() + 12) {
                    accept(suggestions.get(c.index()));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ colours

    private int[] colorize(String value) {
        int[] c = new int[value.length()];
        Arrays.fill(c, DEFAULT);
        Set<String> known = enchants();
        int pos = 0;
        while (pos <= value.length()) {
            int end = value.indexOf('\n', pos);
            if (end < 0) end = value.length();
            colorLine(value, pos, end, c, known);
            pos = end + 1;
        }
        return c;
    }

    private static void colorLine(String v, int s, int e, int[] c, Set<String> known) {
        int i = s, word = 0;
        boolean inEnchants = false, expectLevel = false;
        while (i < e) {
            char ch = v.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (ch == ';') { c[i] = DEFAULT; expectLevel = false; i++; continue; }
            int ts = i;
            while (i < e && !Character.isWhitespace(v.charAt(i)) && v.charAt(i) != ';' && !(inEnchants && v.charAt(i) == ':')) i++;
            if (i == ts) { i++; continue; }   // a lone ':'
            String tok = v.substring(ts, i);
            int color;
            if (word == 0) {
                color = knownItem(tok) ? ITEM : BAD;
            } else if (word == 1) {
                color = tok.matches("\\d+") && Integer.parseInt(tok) >= 1 && Integer.parseInt(tok) <= 2304 ? COUNT : BAD;
                inEnchants = true;
            } else if (expectLevel) {
                color = tok.matches("\\d+") ? COUNT : BAD;
                expectLevel = false;
            } else {
                String n = tok.toLowerCase();
                if (n.startsWith("minecraft:")) n = n.substring(10);
                color = known.isEmpty() || known.contains(n) ? ENCH : BAD;
                expectLevel = true;
            }
            for (int k = ts; k < i; k++) c[k] = color;
            if (i < e && v.charAt(i) == ':') { c[i] = DEFAULT; i++; }
            word++;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, "One per line:  item  count  [enchantment [level];enchantment ...]", width / 2, 24, 0xFF9A9A9A);
        g.centeredText(font, "e.g.  minecraft:diamond_pickaxe 2 efficiency 5;mending   -   leave the enchantments out to take any of that item", width / 2, 36, 0xFF707070);

        drawCode(g);

        g.fill(left, panelY, left + width0, panelY + PANEL_H, 0x80000000);
        chips.clear();
        if (results != null) drawResults(g);
        else if (suggestions.isEmpty()) g.text(font, "Start typing: items and enchantments are suggested here (Tab takes the first).", left + 4, panelY + 4, 0xFF707070);
        else drawChips(g, mouseX, mouseY);

        boolean showNotice = System.currentTimeMillis() < noticeUntil;
        if (showNotice) g.text(font, font.plainSubstrByWidth(notice, width0), left, panelY + PANEL_H + 4, OK);
    }

    private void drawCode(GuiGraphicsExtractor g) {
        if (field == null) return;
        String value = field.value();
        if (!value.equals(colorText)) {
            colorText = value;
            colors = colorize(value);
        }
        int x0 = box.getX() + INNER;
        int y0 = box.getY() + INNER - (int) box.scrollAmount();
        int lineH = font.lineHeight;
        int top = box.getY() + 1, bottom = box.getY() + boxH - 1;
        g.enableScissor(box.getX(), top, box.getX() + box.getWidth(), bottom);
        int row = 0;
        for (Object view : field.iterateLines()) {
            int[] vr = range(view);
            int y = y0 + row * lineH;
            int begin = Math.min(vr[0], value.length()), end = Math.min(vr[1], value.length());
            if (y + lineH >= top && y <= bottom) {
                int x = x0, runStart = begin;
                for (int i = begin; i <= end; i++) {
                    if (i == end || colors[i] != colors[runStart]) {
                        if (i > runStart) {
                            String run = value.substring(runStart, i);
                            g.text(font, run, x, y, colors[runStart], false);
                            x += font.width(run);
                        }
                        runStart = i;
                    }
                }
            }
            row++;
        }
        g.disableScissor();
    }

    private void drawChips(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + 4, y = panelY + 4;
        for (int i = 0; i < suggestions.size(); i++) {
            String s = suggestions.get(i);
            int w = font.width(s) + 8;
            if (x + w > left + width0 - 4) {
                x = left + 4;
                y += 14;
                if (y + 12 > panelY + PANEL_H) break;
            }
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
            g.fill(x, y, x + w, y + 12, i == 0 ? 0xFF2F5F8F : hover ? 0xFF4A4A4A : 0xFF303030);
            g.text(font, s, x + 4, y + 2, 0xFFFFFFFF);
            chips.add(new Chip(x, y, w, i));
            x += w + 4;
        }
    }

    private void drawResults(GuiGraphicsExtractor g) {
        int y = panelY + 4, shown = 0;
        for (int i = 0; i < results.size() - 1 && shown < 2; i++, shown++) {
            Problem p = results.get(i);
            g.text(font, font.plainSubstrByWidth("Line " + p.line() + ": " + p.text(), width0 - 8), left + 4, y, BAD);
            y += 11;
        }
        Problem sum = results.get(results.size() - 1);
        int more = results.size() - 1 - shown;
        boolean ok = results.size() == 1;
        g.text(font, font.plainSubstrByWidth(sum.text() + (more > 0 ? "  (+" + more + " more)" : ""), width0 - 8),
                left + 4, panelY + PANEL_H - 13, ok ? OK : BAD);
    }
}
