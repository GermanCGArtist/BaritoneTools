package dev.ingametest;

import baritone.api.BaritoneAPI;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts a real game with the real addon jars, opens one world and plays through every scenario.
 * A failing scenario does not stop the others; the run fails at the end if any of them failed.
 *
 * Set the environment variable INGAMETEST_ONLY to run only the scenarios whose name contains that text.
 */
@SuppressWarnings("UnstableApiUsage")
public class InGameTests implements FabricClientGameTest {

    private enum Status { PASS, FAIL, SKIP }

    private record Result(String name, Status status, long millis, String detail) {}

    private static final long WHOLE_RUN_LIMIT_MS = 20 * 60_000;
    private static final long CLOSE_LIMIT_MS = 90_000;

    @Override
    public void runTest(ClientGameTestContext context) {
        Watchdog.armed("the whole run", WHOLE_RUN_LIMIT_MS, 2);

        List<Scenario> scenarios = new ArrayList<>(LoadChestScenarios.all());
        String only = System.getenv("INGAMETEST_ONLY");
        List<Result> results = new ArrayList<>();
        int failed;

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            Bot bot = new Bot(context, world.getServer());
            bot.prepareWorld();

            boolean baritoneReady = context.computeOnClient(c -> BaritoneAPI.getProvider().getPrimaryBaritone() != null);
            if (!baritoneReady) throw new AssertionError("Baritone did not start");

            for (Scenario s : scenarios) {
                if (only != null && !only.isBlank() && !s.name().contains(only)) continue;
                if (!FabricLoader.getInstance().isModLoaded(s.modId())) {
                    results.add(new Result(s.name(), Status.SKIP, 0, "mod " + s.modId() + " is not loaded"));
                    continue;
                }
                results.add(run(context, bot, s));
            }

            failed = (int) results.stream().filter(r -> r.status() == Status.FAIL).count();
            boolean nothingRan = results.stream().noneMatch(r -> r.status() == Status.PASS);
            if (nothingRan) failed++;   // a run that tested nothing must not look like a success
            report(results);

            // Closing a test world can deadlock in Fabric's test code (fabric-api #5623, #5655). The results are
            // written by now, so a hang here must not turn into a stuck build.
            Watchdog.armed("closing the world", CLOSE_LIMIT_MS, failed == 0 ? 0 : 1);
        }

        if (failed > 0) throw new AssertionError(failed + " in-game test(s) failed, see ingametest-report.txt");
    }

    private Result run(ClientGameTestContext context, Bot bot, Scenario s) {
        System.out.println("[InGameTest] START " + s.name());
        long start = System.currentTimeMillis();
        try {
            bot.reset();
            s.body().run(bot);
            long took = System.currentTimeMillis() - start;
            System.out.println("[InGameTest] PASS  " + s.name() + " (" + took + " ms)");
            return new Result(s.name(), Status.PASS, took, "");
        } catch (Throwable t) {
            long took = System.currentTimeMillis() - start;
            System.out.println("[InGameTest] FAIL  " + s.name() + ": " + t);
            t.printStackTrace(System.out);
            try {
                context.takeScreenshot("fail-" + s.name());
            } catch (Throwable ignored) {
                // the failure itself is what matters
            }
            return new Result(s.name(), Status.FAIL, took, String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        }
    }

    private static void report(List<Result> results) {
        StringBuilder out = new StringBuilder();
        for (Result r : results) {
            out.append(String.format(Locale.ROOT, "%-4s  %-34s %6.1f s  %s%n", r.status(), r.name(), r.millis() / 1000.0, r.detail()));
        }
        long pass = results.stream().filter(r -> r.status() == Status.PASS).count();
        long fail = results.stream().filter(r -> r.status() == Status.FAIL).count();
        long skip = results.stream().filter(r -> r.status() == Status.SKIP).count();
        out.append(String.format(Locale.ROOT, "%n%d passed, %d failed, %d skipped%n", pass, fail, skip));

        System.out.println("[InGameTest] RESULTS\n" + out);
        Path file = FabricLoader.getInstance().getGameDir().resolve("ingametest-report.txt");
        try {
            Files.writeString(file, out.toString());
        } catch (IOException e) {
            e.printStackTrace(System.out);
        }
    }
}
