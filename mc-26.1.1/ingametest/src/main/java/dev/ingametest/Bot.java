package dev.ingametest;

import baritone.api.BaritoneAPI;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * What a scenario can do: set up the arena, type commands like a player, and look at the result.
 *
 * Results are read from the server, because that is the truth about what really ended up in a chest. Everything runs
 * on the test thread and hops to the client or server thread only for one call, so waiting never blocks either of them.
 */
@SuppressWarnings("UnstableApiUsage")
final class Bot {
    /** Height of the player's feet on the platform. */
    static final int Y = 101;

    private static final int RADIUS = 24;
    private static final int FLOOR = Y - 1;
    private static final int TRIP_START_TICKS = 100;
    static final int TRIP_TICKS = 20 * 60;
    private static final String DEPOSIT_PROCESS = "LoadChest deposit";

    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final Map<String, Integer> given = new HashMap<>();

    Bot(ClientGameTestContext context, TestServerContext server) {
        this.context = context;
        this.server = server;
    }

    // ------------------------------------------------------------------ world

    /** Builds a floating stone platform, independent of what the generated world looks like. */
    void prepareWorld() {
        server.runCommand("gamemode survival @a");
        server.runCommand("difficulty peaceful");
        server.runCommand("time set day");
        server.runCommand("forceload add -32 -32 32 32");

        BlockPos corner = new BlockPos(RADIUS, FLOOR, RADIUS);
        waitUntil("the arena platform to be built", 400, () -> {
            server.runCommand("fill " + -RADIUS + " " + FLOOR + " " + -RADIUS + " " + RADIUS + " " + FLOOR + " " + RADIUS + " minecraft:stone");
            context.waitTicks(5);
            return blockId(corner).equals("stone");
        });

        context.runOnClient(c -> {
            var settings = BaritoneAPI.getSettings();
            settings.allowPlace.value = false;      // walking and mining only: a path must not depend on scaffolding
            settings.allowInventory.value = false;
        });
    }

    /** Clean slate for the next scenario: empty arena and inventory, Baritone idle, LoadChest back to its defaults. */
    void reset() {
        context.runOnClient(c -> {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            if (c.player.containerMenu != c.player.inventoryMenu) c.player.closeContainer();
        });
        server.runCommand("fill " + -RADIUS + " " + Y + " " + -RADIUS + " " + RADIUS + " " + (Y + 9) + " " + RADIUS + " minecraft:air");
        server.runCommand("kill @e[type=item]");
        server.runCommand("clear @a");
        server.runCommand("tp @a 0 " + Y + " 0 0 0");
        given.clear();

        client("loadchest stop");
        client("loadchest clear");
        client("loadchest hotbar off");
        client("loadchest auto off");
        client("loadchest pickup off");
        client("loadchest nearbase off");
        client("loadchest keep remove cobblestone");
        client("loadchest keep dirt 128");
        client("loadchest keep _pickaxe -1");
        client("loadchest keep food -1");

        context.waitTicks(10);
    }

    BlockPos chestAt(int x, int z) {
        BlockPos pos = new BlockPos(x, Y, z);
        server.runCommand("setblock " + x + " " + Y + " " + z + " minecraft:chest");
        waitUntil("the client to see the chest at " + pos.toShortString(), 100,
                () -> context.computeOnClient(c -> BuiltInRegistries.BLOCK.getKey(c.level.getBlockState(pos).getBlock()).getPath().equals("chest")));
        return pos;
    }

    void fillChest(BlockPos chest, String item) {
        for (int i = 0; i < 27; i++) {
            server.runCommand("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                    + " container." + i + " with minecraft:" + item + " 64");
        }
        context.waitTicks(2);
        check("setup: chest filled", 27 * 64, chestItems(chest).getOrDefault(item, 0));
    }

    BlockPos blockAt(int x, int z, String block) {
        BlockPos pos = new BlockPos(x, Y, z);
        server.runCommand("setblock " + x + " " + Y + " " + z + " minecraft:" + block);
        return pos;
    }

    void dropItem(int x, int z, String item, int count) {
        server.runCommand("summon minecraft:item " + x + " " + Y + " " + z
                + " {PickupDelay:0s,Age:0s,Item:{id:\"minecraft:" + item + "\",count:" + count + "}}");
    }

    // ------------------------------------------------------------------ inventory

    /** @param slot "hotbar.0" to "hotbar.8" or "inventory.0" to "inventory.26"; item may carry components: {@code shears[damage=235]} */
    void give(String slot, String item, int count) {
        server.runCommand("item replace entity @a " + slot + " with minecraft:" + item + " " + count);
        given.merge(baseId(item), count, Integer::sum);
    }

    /** Commands fail silently, so make sure the setup really happened before the scenario relies on it. */
    void checkInventorySetup() {
        context.waitTicks(2);
        Map<String, Integer> have = playerItems();
        given.forEach((item, count) -> check("setup: player has " + item, count, have.getOrDefault(item, 0)));
    }

    private static String baseId(String item) {
        int bracket = item.indexOf('[');
        return bracket < 0 ? item : item.substring(0, bracket);
    }

    Map<String, Integer> playerItems() {
        return server.computeOnServer(s -> {
            ServerPlayer p = player(s);
            Map<String, Integer> out = new HashMap<>();
            for (int i = 0; i < 36; i++) add(out, p.getInventory().getItem(i));
            return out;
        });
    }

    /** The item path in an inventory slot (0-8 hotbar, 9-35 main), or "" if empty. */
    String playerSlot(int slot) {
        return server.computeOnServer(s -> {
            ItemStack st = player(s).getInventory().getItem(slot);
            return st.isEmpty() ? "" : path(st);
        });
    }

    Map<String, Integer> chestItems(BlockPos pos) {
        return server.computeOnServer(s -> {
            Map<String, Integer> out = new HashMap<>();
            if (!(s.overworld().getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                throw new AssertionError("no chest at " + pos.toShortString());
            }
            for (int i = 0; i < chest.getContainerSize(); i++) add(out, chest.getItem(i));
            return out;
        });
    }

    Map<String, Integer> groundItems() {
        return server.computeOnServer(s -> {
            Map<String, Integer> out = new HashMap<>();
            AABB box = new AABB(-RADIUS - 8, Y - 8, -RADIUS - 8, RADIUS + 8, Y + 16, RADIUS + 8);
            List<ItemEntity> items = s.overworld().getEntitiesOfClass(ItemEntity.class, box);
            for (ItemEntity e : items) add(out, e.getItem());
            return out;
        });
    }

    String blockId(BlockPos pos) {
        return server.computeOnServer(s -> BuiltInRegistries.BLOCK.getKey(s.overworld().getBlockState(pos).getBlock()).getPath());
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static void add(Map<String, Integer> out, ItemStack st) {
        if (!st.isEmpty()) out.merge(path(st), st.getCount(), Integer::sum);
    }

    private static String path(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
    }

    // ------------------------------------------------------------------ acting like a player

    /** A slash command as the player would type it, without the slash. */
    void client(String command) {
        context.runOnClient(c -> c.player.connection.sendCommand(command));
    }

    /** A Baritone command, without the #. */
    void baritone(String command) {
        boolean accepted = context.computeOnClient(c -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(command));
        if (!accepted) throw new AssertionError("Baritone did not accept: " + command);
    }

    /** Runs a /loadchest command that starts a chest trip and waits until the trip is over. */
    void depositTrip(String command) {
        client(command);
        waitForTripStart();
        waitForTripEnd();
    }

    void waitForTripStart() {
        waitUntil("the chest trip to start", TRIP_START_TICKS, this::tripRunning);
    }

    void waitForTripEnd() {
        waitUntil("the chest trip to finish", TRIP_TICKS, () -> !tripRunning()
                && context.computeOnClient(c -> c.player.containerMenu == c.player.inventoryMenu));
        settle();
    }

    boolean tripRunning() {
        return context.computeOnClient(c -> BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager()
                .mostRecentInControl().map(p -> p.displayName0().equals(DEPOSIT_PROCESS)).orElse(false));
    }

    boolean baritoneBusy() {
        return context.computeOnClient(c -> BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager()
                .mostRecentInControl().isPresent());
    }

    /** Lets clicks and block updates reach the other side before results are read. */
    void settle() {
        context.waitTicks(10);
    }

    void waitTicks(int ticks) {
        context.waitTicks(ticks);
    }

    /** Must be called from the test thread; the condition may hop to the client or server. */
    void waitUntil(String what, int maxTicks, BooleanSupplier condition) {
        for (int tick = 0; tick < maxTicks; tick++) {
            if (condition.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError("Timed out after " + maxTicks + " ticks waiting for " + what);
    }

    // ------------------------------------------------------------------ assertions

    void check(String what, int expected, int actual) {
        if (expected != actual) throw new AssertionError(what + ": expected " + expected + " but found " + actual);
    }

    void check(String what, String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected '" + expected + "' but found '" + actual + "'");
    }

    void check(String what, boolean condition) {
        if (!condition) throw new AssertionError(what);
    }

    void checkContents(String what, Map<String, Integer> actual, Object... itemThenCount) {
        Map<String, Integer> expected = new HashMap<>();
        for (int i = 0; i < itemThenCount.length; i += 2) expected.put((String) itemThenCount[i], (Integer) itemThenCount[i + 1]);
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but found " + actual);
    }
}
