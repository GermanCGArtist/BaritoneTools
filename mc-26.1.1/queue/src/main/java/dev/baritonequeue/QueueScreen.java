package dev.baritonequeue;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * The editor: one command per line, coloured like a code editor. While you type, Baritone's own tab completion is
 * offered below the text (Tab takes the first one, a click takes any); Ctrl+/ comments lines in and out; Test checks
 * the script without running it; presets can be saved from a selection and inserted anywhere.
 * Does not pause a singleplayer world.
 */
final class QueueScreen extends Screen {
    private record Chip(int x, int y, int w, int index) {}

    private static final int GLFW_KEY_TAB = 258;
    private static final int GLFW_KEY_SLASH = 47;
    private static final int PANEL_H = 58;
    private static final int GUTTER = 28;
    private static final int INNER = 4;   // the editor's inner padding

    // kept when the screen is rebuilt (after the preset list)
    private static int pendingCursor = -1;
    private static int rememberedCursorLine = 0;

    private MultiLineEditBox box;
    private MultilineTextField field;          // the editor's text and cursor (reached by reflection: it is private)
    private EditBox presetName;
    private int left, width0, boxY, boxH, panelY;

    private List<String> suggestions = List.of();
    private final List<Chip> chips = new ArrayList<>();
    private String lastKey = "";
    private List<ScriptCheck.Entry> results = null;   // shown instead of suggestions after Test, until the next edit
    private String notice = "";
    private long noticeUntil;

    private String colorText = null;
    private int[] colors = new int[0];

    QueueScreen() {
        super(Component.literal("Baritone command queue"));
    }

    @Override
    protected void init() {
        width0 = Math.min(width - 24, 460);
        left = (width - width0) / 2;
        boxY = 40 + ProgressView.HEIGHT + 6;
        boxH = Math.max(60, height - boxY - PANEL_H - 4 - 16 - 26 - 26 - 6);
        panelY = boxY + boxH + 4;

        // The editor draws its own text almost invisibly (alpha 4: 0 would count as opaque); the coloured copy is
        // painted over it in extractRenderState. Typing, cursor and selection all stay the editor's own.
        box = MultiLineEditBox.builder()
                .setX(left + GUTTER)
                .setY(boxY)
                .setTextColor(0x04FFFFFF)
                .setTextShadow(false)
                .setPlaceholder(Component.literal("One command per line, for example:\nsel replace oak_log air\nsel replace birch_log air\nsel ca"))
                .build(font, width0 - GUTTER, boxH, Component.literal("Commands"));
        box.setCharacterLimit(8000);
        box.setValue(String.join("\n", cfg.script));
        box.setValueListener(text -> {
            store(text);
            results = null;
        });
        addRenderableWidget(box);
        setFocused(box);
        field = textFieldOf(box);
        if (field != null && pendingCursor >= 0) {
            field.seekCursor(Whence.ABSOLUTE, Math.min(pendingCursor, field.value().length()));
        }
        pendingCursor = -1;

        // preset row
        int py = panelY + PANEL_H + 16;
        presetName = new EditBox(font, left, py, width0 - 2 * 84 - 8, 20, Component.literal("Preset name"));
        presetName.setHint(Component.literal("preset name"));
        presetName.setMaxLength(40);
        addRenderableWidget(presetName);
        addRenderableWidget(Button.builder(Component.literal("Save preset"), b -> savePreset())
                .bounds(left + width0 - 2 * 84 - 4, py, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Presets..."), b -> {
            if (field != null) rememberedCursorLine = lineOf(field.value(), Math.min(field.cursor(), field.value().length()));
            store(box.getValue());
            Config.save();
            minecraft.setScreen(new PresetScreen(this));
        }).bounds(left + width0 - 84, py, 84, 20).build());

        // main buttons
        int by = py + 26;
        int gap = 4;
        int bw = (width0 - 4 * gap) / 5;
        addRenderableWidget(Button.builder(Component.literal("Run"), b -> {
            store(box.getValue());
            QueueMod.runner.start();
        }).bounds(left, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test"), b -> test())
                .bounds(left + (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
            if (QueueMod.runner.running()) QueueMod.runner.halt("Queue stopped.");
            Util.baritone().getPathingBehavior().cancelEverything();
        }).bounds(left + 2 * (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Skip step"), b -> QueueMod.runner.skip())
                .bounds(left + 3 * (bw + gap), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + 4 * (bw + gap), by, bw, 20).build());
    }

    private static MultilineTextField textFieldOf(MultiLineEditBox box) {
        try {
            var f = MultiLineEditBox.class.getDeclaredField("textField");
            f.setAccessible(true);
            return (MultilineTextField) f.get(box);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;   // no completion/presets from the cursor then; the editor itself still works
        }
    }

    private void store(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        cfg.script = lines;
    }

    private static java.lang.reflect.Method viewBegin, viewEnd;

    /** {begin, end} of a text range of the editor (its StringView type is not accessible, so by reflection). */
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

    private static int lineOf(String value, int index) {
        int n = 0;
        for (int i = 0; i < index && i < value.length(); i++) if (value.charAt(i) == '\n') n++;
        return n;
    }

    private void say(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 5000;
    }

    // ------------------------------------------------------------------ test

    private void test() {
        store(box.getValue());
        Config.save();
        results = ScriptCheck.run(cfg.script);
        for (ScriptCheck.Entry e : results) {
            if (e.level() != ScriptCheck.Level.OK) Util.msg((e.line() > 0 ? "Line " + e.line() + ": " : "") + e.text());
        }
        if (results.size() == 1) Util.msg(results.get(0).text());
    }

    // ------------------------------------------------------------------ presets

    /** The selected lines (whole lines), or the whole script when nothing is selected. */
    private List<String> selectedLines() {
        String value = box.getValue();
        int a = 0, b = value.length();
        if (field != null && field.hasSelection()) {
            int[] r = range(field.getSelected());
            a = r[0];
            b = r[1];
        }
        int ls = value.lastIndexOf('\n', Math.max(0, a - 1));
        ls = (a == 0) ? 0 : ls + 1;
        int le = value.indexOf('\n', b);
        if (le < 0) le = value.length();
        List<String> lines = new ArrayList<>(List.of(value.substring(ls, le).split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        return lines;
    }

    private void savePreset() {
        String name = presetName.getValue().trim();
        if (name.isEmpty()) { say("Type a name for the preset first."); return; }
        List<String> lines = selectedLines();
        if (lines.isEmpty()) { say("Nothing to save: select some lines first (or write something)."); return; }
        boolean replaced = cfg.presets.containsKey(name);
        cfg.presets.put(name, lines);
        Config.save();
        say((replaced ? "Updated" : "Saved") + " preset '" + name + "' (" + lines.size() + " line" + (lines.size() == 1 ? "" : "s") + ").");
    }

    /** Called by the preset list: puts the preset in at the cursor line; what was there moves below it. */
    void insertPreset(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        List<String> script = new ArrayList<>(cfg.script);
        int at = Math.min(rememberedCursorLine, script.size());
        if (at < script.size() && script.get(at).isBlank()) script.remove(at);   // a blank cursor line is replaced
        script.addAll(at, lines);
        cfg.script = script;
        Config.save();
        int index = 0;   // cursor goes to the start of the line after the inserted block
        for (int i = 0; i < at + lines.size() && i < script.size(); i++) index += script.get(i).length() + 1;
        pendingCursor = index;
    }

    // ------------------------------------------------------------------ completion and comments

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
        String linePrefix = before.substring(before.lastIndexOf('\n') + 1);
        suggestions = Suggest.complete(linePrefix);
    }

    private void accept(String suggestion) {
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        String before = value.substring(0, cursor);
        String partial = Suggest.lastWord(before.substring(before.lastIndexOf('\n') + 1));
        int start = cursor - partial.length();
        String insert = suggestion + " ";
        box.setValue(value.substring(0, start) + insert + value.substring(cursor));
        field.seekCursor(Whence.ABSOLUTE, start + insert.length());
        lastKey = "";   // recompute for the new position
        setFocused(box);
    }

    /** Ctrl+/ : puts "// " in front of the selected lines (or the cursor line), or takes it away again. */
    private void toggleComment() {
        if (field == null) return;
        String value = field.value();
        int cursor = Math.min(field.cursor(), value.length());
        int a = cursor, b = cursor;
        if (field.hasSelection()) {
            int[] r = range(field.getSelected());
            a = r[0];
            b = r[1];
        }
        int ls = a == 0 ? 0 : value.lastIndexOf('\n', a - 1) + 1;
        int le = value.indexOf('\n', b);
        if (le < 0) le = value.length();
        String[] lines = value.substring(ls, le).split("\n", -1);
        boolean allCommented = true;
        for (String l : lines) {
            if (!l.isBlank() && !l.stripLeading().startsWith("//")) allCommented = false;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i > 0) out.append('\n');
            if (l.isBlank()) { out.append(l); continue; }
            if (allCommented) {
                int at = l.indexOf("//");
                String rest = l.substring(at + 2);
                out.append(l, 0, at).append(rest.startsWith(" ") ? rest.substring(1) : rest);
            } else {
                int indent = l.length() - l.stripLeading().length();
                out.append(l, 0, indent).append("// ").append(l.substring(indent));
            }
        }
        String updated = value.substring(0, ls) + out + value.substring(le);
        int delta = updated.length() - value.length();
        box.setValue(updated);
        field.seekCursor(Whence.ABSOLUTE, Math.max(0, Math.min(cursor + delta, updated.length())));
        lastKey = "";
        setFocused(box);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() == box) {
            if (event.key() == GLFW_KEY_SLASH && event.hasControlDown()) {
                toggleComment();
                return true;
            }
            if (event.key() == GLFW_KEY_TAB && !suggestions.isEmpty()) {
                accept(suggestions.get(0));
                return true;
            }
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
        if (box != null) store(box.getValue());
        Config.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, "One command per line, run top to bottom. Also: wait <seconds>, /command, // comment (Ctrl+/ toggles)",
                width / 2, 24, 0xFF9A9A9A);

        ProgressView.draw(g, font, left, 38, width0);
        drawCode(g);

        g.fill(left, panelY, left + width0, panelY + PANEL_H, 0x80000000);
        chips.clear();
        if (results != null) {
            drawResults(g);
        } else if (suggestions.isEmpty()) {
            g.text(font, "Start typing a command: suggestions from Baritone show up here (Tab takes the first).",
                    left + 4, panelY + 4, 0xFF707070);
        } else {
            drawChips(g, mouseX, mouseY);
        }
        boolean showNotice = System.currentTimeMillis() < noticeUntil;
        String status = showNotice ? notice : QueueMod.runner.status();
        g.text(font, font.plainSubstrByWidth(status, width0), left, panelY + PANEL_H + 4, showNotice ? 0xFF60E060 : 0xFFE0E0E0);
    }

    /** The coloured text and the line numbers, painted over the editor's own (invisible) text. */
    private void drawCode(GuiGraphicsExtractor g) {
        if (field == null) return;
        String value = field.value();
        if (!value.equals(colorText)) {
            colorText = value;
            colors = Syntax.colors(value);
        }
        int x0 = box.getX() + INNER;
        int y0 = box.getY() + INNER - (int) box.scrollAmount();
        int lineH = font.lineHeight;
        int top = box.getY() + 1, bottom = box.getY() + boxH - 1;

        g.enableScissor(box.getX(), top, box.getX() + box.getWidth(), bottom);
        int row = 0, logical = 0;
        for (Object view : field.iterateLines()) {
            int[] vr = range(view);
            int y = y0 + row * lineH;
            int begin = Math.min(vr[0], value.length());
            int end = Math.min(vr[1], value.length());
            if (y + lineH >= top && y <= bottom) {
                int x = x0;
                int runStart = begin;
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
            if (vr[0] == 0 || (vr[0] > 0 && value.charAt(vr[0] - 1) == '\n')) {
                logical++;
                if (y + lineH >= top && y <= bottom) {
                    String n = String.valueOf(logical);
                    int state = QueueMod.runner.lineState(logical - 1);   // green = done, blue = running now
                    int color = state == 1 ? 0xFF4CAF50 : state == 2 ? 0xFF4A9EE0 : 0xFF6E7681;
                    if (state == 2) g.fill(left, y - 1, left + 2, y + lineH, color);
                    g.text(font, n, left + GUTTER - 6 - font.width(n), y, color, false);
                }
            }
            row++;
        }
        if (value.isEmpty()) logical = 1;
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
            int bg = i == 0 ? 0xFF2F5F8F : hover ? 0xFF4A4A4A : 0xFF303030;
            g.fill(x, y, x + w, y + 12, bg);
            g.text(font, s, x + 4, y + 2, 0xFFFFFFFF);
            chips.add(new Chip(x, y, w, i));
            x += w + 4;
        }
    }

    private void drawResults(GuiGraphicsExtractor g) {
        int y = panelY + 4;
        int shown = 0;
        // the summary (last entry) always shows; problems before it
        for (int i = 0; i < results.size() - 1 && shown < 4; i++, shown++) {
            ScriptCheck.Entry e = results.get(i);
            int color = e.level() == ScriptCheck.Level.ERROR ? 0xFFFF6060 : 0xFFFFD060;
            String text = "Line " + e.line() + ": " + e.text();
            g.text(font, font.plainSubstrByWidth(text, width0 - 8), left + 4, y, color);
            y += 11;
        }
        int more = results.size() - 1 - shown;
        ScriptCheck.Entry sum = results.get(results.size() - 1);
        int color = sum.level() == ScriptCheck.Level.ERROR ? 0xFFFF6060 : sum.level() == ScriptCheck.Level.WARN ? 0xFFFFD060 : 0xFF60E060;
        g.text(font, font.plainSubstrByWidth(sum.text() + (more > 0 ? "  (+" + more + " more in chat)" : ""), width0 - 8),
                left + 4, panelY + PANEL_H - 13, color);
    }
}
