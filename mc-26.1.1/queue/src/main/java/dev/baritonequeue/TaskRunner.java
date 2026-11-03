package dev.baritonequeue;

import net.minecraft.client.Minecraft;

import static dev.baritonequeue.Config.cfg;

/**
 * Runs the script one step at a time: the next step starts when Baritone has been idle for 2 seconds.
 * Steps: a Baritone command (with or without #), "wait <seconds>", "wait until ...", "/command" for a client or
 * server command, and the control lines of Script (repeat, if/else/end, timeout). "//..." are comments.
 */
final class TaskRunner {
    private static final int START_WAIT = 10;   // ticks for a command to get going before we look at Baritone
    private static final int IDLE_DONE = 40;    // ticks of "nothing running" that count as finished

    private boolean running;
    private Script.Parsed prog = new Script.Parsed();
    private int pc;                             // index of the running step in prog.steps
    private int sinceStart, idle, waitTicks, stepTimeout;
    private String until;                       // "wait until <this>" is running
    private String current = "";
    private Estimate estimate = new Estimate();

    boolean running() {
        return running;
    }

    String status() {
        if (!running) return "Not running.";
        return "Running " + (done() + 1) + "/" + total() + ": " + current;
    }

    /** Steps finished so far (not counting if/else/end/timeout lines; skipped steps count as finished). */
    int done() {
        return prog.actionsBefore[Math.min(pc, prog.steps.size())];
    }

    int total() {
        return prog.actionsBefore[prog.steps.size()];
    }

    String current() { return current; }

    Estimate estimate() { return estimate; }

    /** 0 = still to do, 1 = done, 2 = running now, for the line numbers in the editor. */
    int lineState(int scriptLine) {
        if (!running) return 0;
        for (int i = 0; i < prog.steps.size(); i++) {
            if (prog.steps.get(i).line() == scriptLine) return i < pc ? 1 : i == pc ? 2 : 0;
        }
        return 0;
    }

    /** Finished share of the whole script: every step counts the same, the running one by its own progress. */
    double overall() {
        if (!running || total() == 0) return 0;
        return Math.min(1.0, (done() + Math.max(0, estimate.fraction())) / total());
    }

    void start() {
        if (running) { Util.msg("Already running (" + current + ")."); return; }
        Script.Parsed p = Script.parse(cfg.script);
        if (p.error != null) { Util.msg(p.error); return; }
        if (p.steps.isEmpty()) { Util.msg("Nothing to run. Open the editor (default key J) or use /queue add <command>."); return; }
        prog = p;
        pc = 0;
        stepTimeout = 0;
        running = true;
        next();
    }

    /** Moves on to the step at pc: control lines are handled on the spot, the first real step is started. */
    private void next() {
        Minecraft mc = Minecraft.getInstance();
        while (true) {
            if (pc >= prog.steps.size()) {
                running = false;
                until = null;
                String summary = total() + " step" + (total() == 1 ? "" : "s");
                Util.msg("Queue finished (" + summary + ").");
                System.setProperty("baritonequeue.finished", summary + "|" + System.currentTimeMillis());
                return;
            }
            String c = prog.steps.get(pc).command();
            switch (Script.first(c)) {
                case "end" -> { pc++; continue; }
                case "else" -> { pc = prog.jump[pc] + 1; continue; }
                case "if" -> {
                    boolean yes;
                    try {
                        yes = Script.eval(c.substring(2).trim());
                    } catch (RuntimeException e) {
                        halt("Cannot read the condition '" + c + "'.");
                        return;
                    }
                    pc = yes ? pc + 1 : prog.jump[pc] + 1;
                    continue;
                }
                case "timeout" -> {
                    String arg = c.length() > 7 ? c.substring(8).trim() : "";
                    try {
                        stepTimeout = arg.equals("off") ? 0 : (int) (Double.parseDouble(arg) * 20);
                    } catch (NumberFormatException e) {
                        halt("'" + c + "' is not a number of seconds. Example: timeout 300 (or timeout off)");
                        return;
                    }
                    pc++;
                    continue;
                }
                default -> { }
            }
            current = c;
            break;
        }

        sinceStart = 0;
        idle = 0;
        waitTicks = 0;
        until = null;
        Util.msg("Step " + (done() + 1) + "/" + total() + ": " + current);
        estimate = Estimate.of(current);

        String lower = current.toLowerCase();
        if (lower.startsWith("wait until ")) {
            String what = current.substring(11).trim();
            String err = Script.checkUntil(what);
            if (err != null) halt(err);
            else until = what;
            return;
        }
        if (lower.startsWith("wait ")) {
            try {
                waitTicks = Math.max(1, (int) (Double.parseDouble(current.substring(5).trim()) * 20));
            } catch (NumberFormatException e) {
                halt("'" + current + "' is not a number of seconds. Example: wait 30");
            }
            return;
        }
        if (current.startsWith("/")) {   // a client or server command; it takes no time
            if (mc.player != null) mc.player.connection.sendCommand(current.substring(1));
            return;
        }
        if (!execute(current)) halt("Baritone did not accept '" + current + "' (check the spelling). Fix it and run /queue run again.");
    }

    private static boolean execute(String command) {
        try {
            return Util.baritone().getCommandManager().execute(command);
        } catch (Throwable t) {
            return false;
        }
    }

    private void advance() {
        pc++;
        next();
    }

    private boolean awaitRecovery;
    private int recoveryWait;

    /** Called while the connection is down: keep the step, wait for SurvivalKit to bring us back. */
    void holdForReconnect() {
        if (!running) return;
        awaitRecovery = true;
        recoveryWait = 0;
    }

    /** After a death or a reconnect the SurvivalKit addon (if installed) gets you back and then signals; the step is run again. */
    private boolean recovering() {
        var p = Minecraft.getInstance().player;
        if (p != null && p.isDeadOrDying()) {
            awaitRecovery = true;
            recoveryWait = 0;
            return true;
        }
        if (!awaitRecovery) return false;
        if (System.getProperty("survivalkit.safe") != null) {
            System.clearProperty("survivalkit.safe");
            awaitRecovery = false;
            if (waitTicks > 0 || until != null) return false;   // a wait step just carries on
            if (current.startsWith("/")) { advance(); return true; }
            sinceStart = 0;
            idle = 0;
            Util.msg("Resuming step " + (done() + 1) + "/" + total() + ": " + current);
            if (!execute(current)) halt("Baritone did not accept '" + current + "' after the respawn.");
            return true;
        }
        if (System.getProperty("survivalkit.recovering") != null) {
            recoveryWait = 0;
        } else if (++recoveryWait > 100) {   // nobody is bringing us back
            awaitRecovery = false;
            halt("The queue stopped (you died or the connection dropped). /queue run starts it again.");
        }
        return true;
    }

    void tick() {
        if (running) System.setProperty("baritonequeue.active", "1");
        else System.clearProperty("baritonequeue.active");
        String reason = System.getProperty("baritonequeue.halt");   // set by other addons (safe-area stop, water loop)
        if (reason != null) {
            System.clearProperty("baritonequeue.halt");
            halt(reason);
        }
        if (!running) return;
        if (recovering()) return;
        estimate.tick();
        if (until != null) {
            if (Script.reached(until)) { until = null; advance(); }
            return;
        }
        if (waitTicks > 0) {
            if (--waitTicks == 0) advance();
            return;
        }
        if (++sinceStart < START_WAIT) return;
        if (current.startsWith("/")) { advance(); return; }
        if (stepTimeout > 0 && sinceStart > stepTimeout) {
            Util.warn("queue", "Step '" + current + "' took longer than " + stepTimeout / 20 + " s, so I stopped it and go on.");
            Util.baritone().getPathingBehavior().cancelEverything();
            advance();
            return;
        }
        if (Util.baritoneBusy()) { idle = 0; return; }
        if (++idle < IDLE_DONE) return;
        advance();
    }

    /** Stops running; the script itself is untouched. */
    void halt(String why) {
        if (!running) return;
        running = false;
        Util.warn("queue", why);
    }

    /** Ends the current task and goes on with the next line. */
    void skip() {
        if (!running) { Util.msg("The queue is not running."); return; }
        Util.baritone().getPathingBehavior().cancelEverything();
        advance();
    }

    void reset() {
        running = false;
        awaitRecovery = false;
        System.clearProperty("baritonequeue.active");
    }
}
