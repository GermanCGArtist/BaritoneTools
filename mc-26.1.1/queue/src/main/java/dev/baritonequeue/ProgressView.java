package dev.baritonequeue;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The progress display: one bar cut into a piece per step (green = done, blue = the running one, filled by its own
 * progress), the share of the whole script, and a second bar for the running step. Used in the editor and as an
 * overlay while the queue runs.
 */
final class ProgressView {
    private ProgressView() {}

    static final int HEIGHT = 30;

    private static final int BACK = 0xFF2A2A2A, DONE = 0xFF4CAF50, NOW = 0xFF4A9EE0, EDGE = 0xFF000000;

    static void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
        TaskRunner r = QueueMod.runner;
        if (!r.running()) {
            g.text(font, "Progress shows here while the queue runs.", x, y + 10, 0xFF707070);
            return;
        }
        Estimate e = r.estimate();
        int total = r.total(), done = r.done();
        double f = Math.max(0, e.fraction());

        String head = "Step " + Math.min(done + 1, total) + " of " + total + "  -  " + Math.round(r.overall() * 100) + "% overall";
        g.text(font, head, x, y, 0xFFFFFFFF, false);

        // the overall bar, one piece per step
        int by = y + 10, bh = 6;
        g.fill(x - 1, by - 1, x + w + 1, by + bh + 1, EDGE);
        if (total > 0 && w / total >= 3) {
            for (int i = 0; i < total; i++) {
                int x1 = x + (int) ((long) w * i / total), x2 = x + (int) ((long) w * (i + 1) / total) - 1;
                g.fill(x1, by, x2, by + bh, BACK);
                if (i < done) g.fill(x1, by, x2, by + bh, DONE);
                else if (i == done) g.fill(x1, by, x1 + (int) ((x2 - x1) * f), by + bh, NOW);
            }
        } else {   // too many steps for pieces
            g.fill(x, by, x + w, by + bh, BACK);
            g.fill(x, by, x + (int) (w * r.overall()), by + bh, DONE);
        }

        // the running step
        String pct = e.known() ? Math.round(f * 100) + "%" : "running";
        String left = e.left();
        long eta = e.etaTicks();
        StringBuilder sb = new StringBuilder(pct);
        if (!left.isEmpty()) sb.append("  -  ").append(left);
        if (eta >= 0) sb.append("  -  about ").append(Estimate.duration(eta)).append(" left");
        else if (!e.known()) sb.append("  -  ").append(Estimate.duration(e.elapsedTicks()));
        String tail = sb.toString();
        int tailW = font.width(tail);
        String cmd = font.plainSubstrByWidth(r.current(), Math.max(20, w - tailW - 10));
        g.text(font, cmd, x, y + 19, 0xFFDDDDDD, false);
        g.text(font, tail, x + w - tailW, y + 19, 0xFFAAAAAA, false);

        int sy = y + 28;
        g.fill(x - 1, sy - 1, x + w + 1, sy + 3, EDGE);
        g.fill(x, sy, x + w, sy + 2, BACK);
        if (e.known()) g.fill(x, sy, x + (int) (w * f), sy + 2, NOW);
        else {   // unknown length: a block that moves back and forth
            int span = Math.max(8, w / 6);
            long t = System.currentTimeMillis() / 12;
            int pos = (int) (t % (2L * (w - span)));
            if (pos > w - span) pos = 2 * (w - span) - pos;
            g.fill(x + pos, sy, x + pos + span, sy + 2, NOW);
        }
    }
}
