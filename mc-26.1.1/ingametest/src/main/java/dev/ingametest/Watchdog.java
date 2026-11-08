package dev.ingametest;

/** Ends the game if it hangs, so a stuck test can never block a build forever. Dumps all threads first. */
final class Watchdog {
    private Watchdog() {}

    static void armed(String what, long limitMs, int exitCode) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(limitMs);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("[InGameTest] WATCHDOG: " + what + " took longer than " + limitMs / 1000 + " s, ending the game.");
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                System.out.println("--- " + thread.getName() + " (" + thread.getState() + ")");
                for (StackTraceElement e : stack) System.out.println("    at " + e);
            });
            System.out.flush();
            Runtime.getRuntime().halt(exitCode);
        }, "InGameTest watchdog");
        t.setDaemon(true);
        t.start();
    }
}
