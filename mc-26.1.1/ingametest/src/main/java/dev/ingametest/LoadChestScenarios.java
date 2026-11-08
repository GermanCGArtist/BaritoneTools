package dev.ingametest;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Each scenario types the real commands and lets Baritone really walk; what counts is what ends up where. */
final class LoadChestScenarios {
    private LoadChestScenarios() {}

    private static final String MOD = "loadchest";

    static List<Scenario> all() {
        return List.of(
                new Scenario("loadchest_keep_rules_and_split", MOD, LoadChestScenarios::keepRulesAndSplit),
                new Scenario("loadchest_keep_all", MOD, LoadChestScenarios::keepAll),
                new Scenario("loadchest_worn_out_tool", MOD, LoadChestScenarios::wornOutTool),
                new Scenario("loadchest_hotbar_off", MOD, LoadChestScenarios::hotbarOff),
                new Scenario("loadchest_hotbar_on", MOD, LoadChestScenarios::hotbarOn),
                new Scenario("loadchest_filter", MOD, LoadChestScenarios::filter),
                new Scenario("loadchest_mined_baseline", MOD, LoadChestScenarios::minedBaseline),
                new Scenario("loadchest_nearest_chest", MOD, LoadChestScenarios::nearestChest),
                new Scenario("loadchest_saved_chest_wins", MOD, LoadChestScenarios::savedChestWins),
                new Scenario("loadchest_full_chest_overflow", MOD, LoadChestScenarios::fullChestOverflow),
                new Scenario("loadchest_stop_cancels_trip", MOD, LoadChestScenarios::stopCancelsTrip),
                new Scenario("loadchest_auto_empty_resumes_mine", MOD, LoadChestScenarios::autoEmptyResumesMine),
                new Scenario("loadchest_pickup_only_mined", MOD, LoadChestScenarios::pickupOnlyMined)
        );
    }

    private static String at(BlockPos chest) {
        return "loadchest at " + chest.getX() + " " + chest.getY() + " " + chest.getZ();
    }

    /** The keep limit counts the hotbar too, and a stack that must be split is split exactly. */
    private static void keepRulesAndSplit(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("hotbar.0", "iron_pickaxe", 1);
        b.give("hotbar.1", "dirt", 64);
        b.give("inventory.0", "dirt", 64);
        b.give("inventory.1", "dirt", 64);
        b.give("inventory.2", "dirt", 8);
        b.give("inventory.3", "cobblestone", 64);
        b.give("inventory.4", "cobblestone", 64);
        b.give("inventory.5", "bread", 16);
        b.give("inventory.6", "shears", 1);
        b.checkInventorySetup();

        b.depositTrip(at(chest));

        b.checkContents("chest", b.chestItems(chest), "cobblestone", 128, "dirt", 72);
        b.checkContents("player", b.playerItems(), "iron_pickaxe", 1, "dirt", 128, "bread", 16, "shears", 1);
    }

    private static void keepAll(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();
        b.client("loadchest keep cobblestone -1");

        b.depositTrip(at(chest));

        b.checkContents("chest", b.chestItems(chest));
        b.checkContents("player", b.playerItems(), "cobblestone", 64);
    }

    private static void wornOutTool(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("inventory.0", "shears[damage=235]", 1);
        b.give("inventory.1", "iron_axe", 1);
        b.checkInventorySetup();

        b.depositTrip(at(chest));

        b.checkContents("chest", b.chestItems(chest), "shears", 1);
        b.checkContents("player", b.playerItems(), "iron_axe", 1);
    }

    private static void hotbarOff(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("hotbar.0", "cobblestone", 64);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();

        b.depositTrip(at(chest));

        b.checkContents("chest", b.chestItems(chest), "cobblestone", 64);
        b.check("hotbar stack stays", "cobblestone", b.playerSlot(0));
        b.check("main stack left", "", b.playerSlot(9));
    }

    private static void hotbarOn(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("hotbar.0", "cobblestone", 64);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();
        b.client("loadchest hotbar on");

        b.depositTrip(at(chest));

        b.checkContents("chest", b.chestItems(chest), "cobblestone", 128);
        b.checkContents("player", b.playerItems());
    }

    private static void filter(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("inventory.0", "cobblestone", 64);
        b.give("inventory.1", "andesite", 64);
        b.checkInventorySetup();

        b.depositTrip(at(chest) + " cobble");

        b.checkContents("chest", b.chestItems(chest), "cobblestone", 64);
        b.checkContents("player", b.playerItems(), "andesite", 64);
    }

    /** Only what was gained after the baseline leaves, even when that means splitting a stack. */
    private static void minedBaseline(Bot b) {
        BlockPos chest = b.chestAt(8, 0);
        b.give("inventory.0", "cobblestone", 10);
        b.checkInventorySetup();
        b.client("loadchest baseline");
        b.give("inventory.1", "cobblestone", 5);
        b.checkInventorySetup();

        b.depositTrip("loadchest mined");

        b.checkContents("chest", b.chestItems(chest), "cobblestone", 5);
        b.checkContents("player", b.playerItems(), "cobblestone", 10);
    }

    private static void nearestChest(Bot b) {
        BlockPos near = b.chestAt(6, 0);
        BlockPos far = b.chestAt(-14, 0);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();

        b.depositTrip("loadchest nearest");

        b.checkContents("near chest", b.chestItems(near), "cobblestone", 64);
        b.checkContents("far chest", b.chestItems(far));
    }

    private static void savedChestWins(Bot b) {
        BlockPos near = b.chestAt(6, 0);
        BlockPos saved = b.chestAt(-14, 0);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();
        b.client("loadchest set " + saved.getX() + " " + saved.getY() + " " + saved.getZ());

        b.depositTrip("loadchest");

        b.checkContents("saved chest", b.chestItems(saved), "cobblestone", 64);
        b.checkContents("near chest", b.chestItems(near));
    }

    private static void fullChestOverflow(Bot b) {
        BlockPos full = b.chestAt(8, 0);
        b.fillChest(full, "stone");
        BlockPos spare = b.chestAt(8, 5);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();
        b.client("loadchest set " + full.getX() + " " + full.getY() + " " + full.getZ());

        b.depositTrip("loadchest");

        b.checkContents("spare chest", b.chestItems(spare), "cobblestone", 64);
        b.checkContents("full chest", b.chestItems(full), "stone", 27 * 64);
        b.checkContents("player", b.playerItems());
    }

    private static void stopCancelsTrip(Bot b) {
        BlockPos chest = b.chestAt(22, 0);
        b.give("inventory.0", "cobblestone", 64);
        b.checkInventorySetup();

        b.client(at(chest));
        b.waitForTripStart();
        b.waitTicks(20);
        b.baritone("stop");
        b.waitUntil("the trip to end after #stop", 40, () -> !b.tripRunning());
        b.waitTicks(100);

        b.check("still going after #stop", !b.tripRunning());
        b.checkContents("chest", b.chestItems(chest));
        b.checkContents("player", b.playerItems(), "cobblestone", 64);
    }

    /** A chest trip interrupts #mine but must not end it. */
    private static void autoEmptyResumesMine(Bot b) {
        BlockPos chest = b.chestAt(4, 0);
        List<BlockPos> logs = List.of(
                b.blockAt(-12, 18, "oak_log"), b.blockAt(-6, 18, "oak_log"), b.blockAt(0, 18, "oak_log"),
                b.blockAt(6, 18, "oak_log"), b.blockAt(12, 18, "oak_log"), b.blockAt(18, 18, "oak_log"));
        for (int i = 0; i < 9; i++) b.give("hotbar." + i, "andesite", 1);
        for (int i = 0; i < 27; i++) b.give("inventory." + i, "cobblestone", 64);
        b.checkInventorySetup();
        b.client("loadchest set " + chest.getX() + " " + chest.getY() + " " + chest.getZ());
        b.client("loadchest auto on");

        b.baritone("mine oak_log");
        b.waitUntil("auto-empty to start a chest trip", 200, b::tripRunning);
        b.waitForTripEnd();

        b.checkContents("chest", b.chestItems(chest), "cobblestone", 27 * 64);
        long left = logs.stream().filter(p -> b.blockId(p).equals("oak_log")).count();
        b.check("the trip must happen before the mining is done (logs left: " + left + ")", left > 0);

        b.waitUntil("#mine to carry on and break every log", 20 * 120,
                () -> logs.stream().noneMatch(p -> b.blockId(p).equals("oak_log")));
    }

    /** Pickup "mined" collects what Baritone broke and leaves an unrelated item lying. */
    private static void pickupOnlyMined(Bot b) {
        List<BlockPos> logs = List.of(b.blockAt(6, 10, "oak_log"), b.blockAt(12, 10, "oak_log"), b.blockAt(18, 10, "oak_log"));
        b.dropItem(-20, -20, "emerald", 3);
        b.waitTicks(5);
        b.check("setup: emerald on the ground", b.groundItems().getOrDefault("emerald", 0) == 3);
        b.client("loadchest pickup mined");

        b.baritone("mine oak_log");
        b.waitUntil("every log to be broken", 20 * 120, () -> logs.stream().noneMatch(p -> b.blockId(p).equals("oak_log")));
        b.waitUntil("the logs to be collected", 20 * 40, () -> b.playerItems().getOrDefault("oak_log", 0) == logs.size());
        b.waitUntil("Baritone to go idle", 20 * 30, () -> !b.baritoneBusy());

        b.check("the emerald was not mined, so it must stay", b.groundItems().getOrDefault("emerald", 0) == 3);
        b.check("no emerald in the inventory", !b.playerItems().containsKey("emerald"));
    }
}
