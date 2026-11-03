package dev.baritoneui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** One line of the screen: a label on the left and some controls on the right. */
interface Row {
    String label();

    /** Adds the controls; (x, y) is the top-left of the control area and w its width. */
    void controls(UiScreen s, int x, int y, int w);

    /** Explanation shown when the mouse is over the row; null = none. */
    default String tip() { return null; }

    /** The same row, with a hover explanation. */
    static Row tip(Row row, String tip) {
        return new Row() {
            public String label() { return row.label(); }
            public String tip() { return tip; }
            public void controls(UiScreen s, int x, int y, int w) { row.controls(s, x, y, w); }
        };
    }

    // ------------------------------------------------------------------ factories

    static Component onOff(boolean on) {
        return Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    /** Text only. */
    static Row info(String text) {
        return new Row() {
            public String label() { return text; }
            public void controls(UiScreen s, int x, int y, int w) {}
        };
    }

    /** ON/OFF button. `get` is read again after each click, so it always shows the real state. */
    static Row bool(String label, BooleanSupplier get, Consumer<Boolean> set) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(Button.builder(onOff(get.getAsBoolean()), btn -> {
                    set.accept(!get.getAsBoolean());
                    btn.setMessage(onOff(get.getAsBoolean()));
                }).bounds(x, y, w, 20).build());
            }
        };
    }

    /** Button that steps through a list of choices. */
    static Row cycle(String label, List<String> options, IntSupplier get, IntConsumer set) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(Button.builder(Component.literal(options.get(get.getAsInt())), btn -> {
                    set.accept((get.getAsInt() + 1) % options.size());
                    btn.setMessage(Component.literal(options.get(get.getAsInt())));
                }).bounds(x, y, w, 20).build());
            }
        };
    }

    /** Text field; `set` is called with every edit and may throw to mark the input as invalid (turns red). */
    static Row number(String label, Supplier<String> get, Consumer<String> set) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(field(s, x, y, w, get.get(), set));
            }
        };
    }

    /** Like number(), plus a small X button that removes the entry. */
    static Row removable(String label, Supplier<String> get, Consumer<String> set, Runnable remove) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(field(s, x, y, w - 24, get.get(), set));
                s.add(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED), btn -> {
                    remove.run();
                    s.refresh();
                }).bounds(x + w - 20, y, 20, 20).build());
            }
        };
    }

    /** A plain button. */
    static Row action(String label, String buttonText, Runnable run) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(Button.builder(Component.literal(buttonText), btn -> run.run()).bounds(x, y, w, 20).build());
            }
        };
    }

    /** A plain button that changes the list afterwards (the rows are rebuilt). */
    static Row actionRefresh(String label, String buttonText, Runnable run) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(Button.builder(Component.literal(buttonText), btn -> {
                    run.run();
                    s.refresh();
                }).bounds(x, y, w, 20).build());
            }
        };
    }

    /** A line with a red Remove button. */
    static Row removeButton(String label, Runnable remove) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                s.add(Button.builder(Component.literal("Remove").withStyle(ChatFormatting.RED), btn -> {
                    remove.run();
                    s.refresh();
                }).bounds(x, y, w, 20).build());
            }
        };
    }

    /** One free-text field and an Add button. */
    static Row addText(String label, String hint, Consumer<String> add) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                EditBox box = s.add(new EditBox(s.font(), x, y, w - 42, 20, Component.literal(label)));
                box.setHint(Component.literal(hint));
                box.setMaxLength(200);
                s.add(Button.builder(Component.literal("Add"), btn -> {
                    String text = box.getValue().trim();
                    if (text.isEmpty()) return;
                    add.accept(text);
                    s.refresh();
                }).bounds(x + w - 38, y, 38, 20).build());
            }
        };
    }

    /** "item" + "count" fields and an Add button. */
    static Row addRule(String label, String defaultCount, BiConsumer<String, Integer> add) {
        return new Row() {
            public String label() { return label; }
            public void controls(UiScreen s, int x, int y, int w) {
                EditBox item = s.add(new EditBox(s.font(), x, y, w - 78, 20, Component.literal("item")));
                item.setHint(Component.literal("item"));
                EditBox count = s.add(new EditBox(s.font(), x + w - 74, y, 32, 20, Component.literal("count")));
                count.setValue(defaultCount);
                s.add(Button.builder(Component.literal("Add"), btn -> {
                    String name = item.getValue().trim().toLowerCase();
                    if (name.isEmpty()) return;
                    try {
                        add.accept(name, Integer.parseInt(count.getValue().trim()));
                        s.refresh();
                    } catch (NumberFormatException e) {
                        count.setTextColor(0xFFFF5555);
                    }
                }).bounds(x + w - 38, y, 38, 20).build());
            }
        };
    }

    private static EditBox field(UiScreen s, int x, int y, int w, String value, Consumer<String> set) {
        EditBox box = new EditBox(s.font(), x, y, w, 20, Component.empty());
        box.setValue(value);
        box.setResponder(text -> {
            if (text.isBlank()) return;
            try {
                set.accept(text.trim());
                box.setTextColor(0xFFE0E0E0);
            } catch (Exception e) {
                box.setTextColor(0xFFFF5555);
            }
        });
        return box;
    }
}
