package dev.baritoneui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Baritone UI: five tabs (common settings, every setting, LoadChest, SurvivalKit, BuildTools), a search box and a scrolling
 * list of rows. It does not pause a singleplayer world, so a running task keeps going while it is open.
 */
final class UiScreen extends Screen {
    private enum Tab {
        COMMON("Common"), ALL("All settings"), LOADCHEST("LoadChest"), SURVIVAL("SurvivalKit"), BUILD("BuildTools");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    private record Label(String text, String tip, int x, int y, int width) {}

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 74;

    private static Tab lastTab = Tab.COMMON;   // reopen where you left off

    private Tab tab = lastTab;
    private String filter = "";
    private int scroll;
    private int perPage = 1;
    private List<Row> allRows = List.of();
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();

    UiScreen() {
        super(Component.literal("Baritone UI"));
    }

    Font font() {
        return font;
    }

    <T extends AbstractWidget> T add(T widget) {
        rowWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    @Override
    protected void init() {
        rowWidgets.clear();

        int buttonWidth = 80, gap = 4;
        Tab[] tabs = Tab.values();
        int x0 = (width - (tabs.length * buttonWidth + (tabs.length - 1) * gap)) / 2;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            Button b = addRenderableWidget(Button.builder(Component.literal(t.title), btn -> {
                tab = t;
                lastTab = t;
                scroll = 0;
                rebuildWidgets();
            }).bounds(x0 + i * (buttonWidth + gap), 22, buttonWidth, 20).build());
            b.active = t != tab;
        }

        EditBox search = addRenderableWidget(new EditBox(font, width / 2 - 90, 48, 180, 18, Component.literal("Search")));
        search.setHint(Component.literal("Search..."));
        search.setValue(filter);
        search.setResponder(text -> {
            filter = text;
            scroll = 0;
            layoutRows();
        });

        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(width / 2 - 100, height - 26, 200, 20).build());

        refresh();
    }

    /** Re-reads the current tab (after a command changed something) and lays it out again. */
    void refresh() {
        allRows = switch (tab) {
            case COMMON -> Tabs.common();
            case ALL -> Tabs.all();
            case LOADCHEST -> Tabs.loadChest();
            case SURVIVAL -> Tabs.survivalKit();
            case BUILD -> Tabs.buildTools();
        };
        layoutRows();
    }

    private List<Row> visibleRows() {
        String f = filter.trim().toLowerCase();
        return f.isEmpty() ? allRows : allRows.stream().filter(r -> r.label().toLowerCase().contains(f)).toList();
    }

    private void layoutRows() {
        for (AbstractWidget w : rowWidgets) removeWidget(w);
        rowWidgets.clear();
        labels.clear();

        List<Row> rows = visibleRows();
        perPage = Math.max(1, (height - 44 - LIST_TOP) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, rows.size() - perPage));

        int contentWidth = Math.min(width - 24, 420);
        int controlWidth = 170;
        int left = (width - contentWidth) / 2;
        for (int i = scroll; i < Math.min(rows.size(), scroll + perPage); i++) {
            int y = LIST_TOP + (i - scroll) * ROW_HEIGHT;
            Row row = rows.get(i);
            labels.add(new Label(row.label(), row.tip(), left, y, contentWidth - controlWidth - 6));
            row.controls(this, left + contentWidth - controlWidth, y, controlWidth);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        layoutRows();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // the same key that opened the screen closes it (unless you are typing in a field)
        boolean typing = getFocused() instanceof EditBox box && box.isFocused();
        if (!typing && BaritoneUiMod.open.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        for (Label l : labels) g.text(font, font.plainSubstrByWidth(l.text(), l.width()), l.x(), l.y() + 6, 0xFFE0E0E0);
        // hovering over the label (or its row) explains the setting
        for (Label l : labels) {
            if (l.tip() != null && mouseY >= l.y() && mouseY < l.y() + ROW_HEIGHT - 2 && mouseX >= l.x() && mouseX < l.x() + l.width() + 176) {
                g.setTooltipForNextFrame(font, font.split(Component.literal(l.tip()), 220), mouseX, mouseY);
                break;
            }
        }

        List<Row> rows = visibleRows();
        String footer = rows.isEmpty()
                ? "Nothing matches."
                : "Showing " + (scroll + 1) + "-" + Math.min(rows.size(), scroll + perPage) + " of " + rows.size()
                + (rows.size() > perPage ? "  (mouse wheel to scroll)" : "");
        g.centeredText(font, footer, width / 2, height - 40, 0xFF9A9A9A);
    }
}
