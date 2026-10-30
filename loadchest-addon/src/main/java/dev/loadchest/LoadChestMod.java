package dev.loadchest;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Baritone addon: /loadchest
 *
 * Walks to a chest (saved, given by coordinates, or nearest), opens it and deposits items.
 * What stays in the inventory is controlled by:
 *   - keep rules   (/loadchest keep dirt 128  -> always keep 128 dirt, deposit the rest)
 *   - hotbar toggle (/loadchest hotbar off    -> never touch hotbar slots)
 *   - mined mode   (/loadchest baseline ... /loadchest mined -> only deposit what was gained since baseline)
 */
public class LoadChestMod implements ClientModInitializer {

    private enum State { IDLE, PATHING, OPENING, DEPOSITING }

    private record Action(int slot, int button, SlotActionType type) {}

    /** Persisted settings (config/loadchest.json). keep value -1 = keep ALL matching items. */
    private static class Config {
        boolean includeHotbar = false;
        int[] chest = null;
        Map<String, Integer> keep = new LinkedHashMap<>();
    }

    private static final int SEARCH_RADIUS = 48;
    private static final int PATH_TIMEOUT = 20 * 60;   // 60 s
    private static final int OPEN_TIMEOUT = 20 * 5;    // 5 s

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config cfg = new Config();
    private static final Map<String, Integer> baseline = new HashMap<>();
    private static boolean baselineSet = false;

    private State state = State.IDLE;
    private BlockPos target;
    private String filter = "";
    private boolean minedOnly = false;
    private int timer = 0;
    private ArrayDeque<Action> queue = null;

    // ------------------------------------------------------------------ init

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("loadchest")
                        .executes(ctx -> start("", null, false))

                        // ---- chest selection
                        .then(ClientCommandManager.literal("set")
                                .executes(ctx -> setLookedAt())
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            BlockPos p = posOf(ctx);
                                            cfg.chest = new int[]{p.getX(), p.getY(), p.getZ()};
                                            saveConfig();
                                            msg("Chest saved: " + p.toShortString());
                                            return 1;
                                        })))))
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            cfg.chest = null;
                            saveConfig();
                            msg("Saved chest cleared (using nearest chest).");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("nearest")
                                .executes(ctx -> start("", findNearestChest(MinecraftClient.getInstance()), false))
                                .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                                        .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"),
                                                findNearestChest(MinecraftClient.getInstance()), false))))
                        .then(ClientCommandManager.literal("at")
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> start("", posOf(ctx), false))
                                        .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                                                .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"),
                                                        posOf(ctx), false)))))))

                        // ---- mined-only mode
                        .then(ClientCommandManager.literal("baseline").executes(ctx -> {
                            takeBaseline();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("mined")
                                .executes(ctx -> start("", null, true))
                                .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                                        .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"), null, true))))

                        // ---- hotbar toggle
                        .then(ClientCommandManager.literal("hotbar")
                                .then(ClientCommandManager.literal("on").executes(ctx -> {
                                    cfg.includeHotbar = true;
                                    saveConfig();
                                    msg("Hotbar will be deposited too.");
                                    return 1;
                                }))
                                .then(ClientCommandManager.literal("off").executes(ctx -> {
                                    cfg.includeHotbar = false;
                                    saveConfig();
                                    msg("Hotbar is left alone.");
                                    return 1;
                                })))

                        // ---- keep rules
                        .then(ClientCommandManager.literal("keep")
                                .then(ClientCommandManager.literal("list").executes(ctx -> {
                                    if (cfg.keep.isEmpty()) msg("No keep rules.");
                                    cfg.keep.forEach((k, v) -> msg("keep " + k + ": " + (v < 0 ? "all" : v)));
                                    msg("Hotbar: " + (cfg.includeHotbar ? "deposited" : "left alone"));
                                    return 1;
                                }))
                                .then(ClientCommandManager.literal("remove")
                                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String k = StringArgumentType.getString(ctx, "item").toLowerCase();
                                                    if (cfg.keep.remove(k) != null) {
                                                        saveConfig();
                                                        msg("Removed keep rule: " + k);
                                                    } else msg("No such rule: " + k);
                                                    return 1;
                                                })))
                                .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(-1))
                                                .executes(ctx -> {
                                                    String k = StringArgumentType.getString(ctx, "item").toLowerCase();
                                                    int n = IntegerArgumentType.getInteger(ctx, "count");
                                                    cfg.keep.put(k, n);
                                                    saveConfig();
                                                    msg("Keep " + (n < 0 ? "all" : n) + " x " + k);
                                                    return 1;
                                                }))))

                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            stop("Stopped.");
                            return 1;
                        }))

                        // ---- /loadchest <filter>
                        .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                                .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"), null, false)))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ------------------------------------------------------------------ config

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("loadchest.json");
    }

    private static void loadConfig() {
        try {
            Path p = configPath();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) cfg = c;
                }
            } else {
                // defaults: dirt as scaffolding, never deposit pickaxes or food
                cfg.keep.put("dirt", 128);
                cfg.keep.put("_pickaxe", -1);
                cfg.keep.put("@food", -1);
                saveConfig();
            }
            if (cfg.keep == null) cfg.keep = new LinkedHashMap<>();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveConfig() {
        try (Writer w = Files.newBufferedWriter(configPath())) {
            GSON.toJson(cfg, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ commands

    private BlockPos posOf(CommandContext<?> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private int setLookedAt() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;
        if (mc.crosshairTarget instanceof BlockHitResult hit
                && mc.world.getBlockState(hit.getBlockPos()).getBlock() instanceof ChestBlock) {
            BlockPos p = hit.getBlockPos();
            cfg.chest = new int[]{p.getX(), p.getY(), p.getZ()};
            saveConfig();
            msg("Chest saved: " + p.toShortString());
            return 1;
        }
        msg("Look at a chest first (or use /loadchest set <x> <y> <z>).");
        return 0;
    }

    private void takeBaseline() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        baseline.clear();
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty()) baseline.merge(idOf(st), st.getCount(), Integer::sum);
        }
        baselineSet = true;
        msg("Baseline saved. /loadchest mined will deposit only items gained after this point.");
    }

    private int start(String arg, BlockPos explicit, boolean mined) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;

        if (mined && !baselineSet) {
            msg("No baseline yet. Run /loadchest baseline before you start mining.");
            return 0;
        }
        filter = arg.toLowerCase();
        minedOnly = mined;

        BlockPos saved = cfg.chest == null ? null : new BlockPos(cfg.chest[0], cfg.chest[1], cfg.chest[2]);
        target = explicit != null ? explicit : (saved != null ? saved : findNearestChest(mc));
        if (target == null) {
            msg("No chest found within " + SEARCH_RADIUS + " blocks.");
            return 0;
        }
        if (!(mc.world.getBlockState(target).getBlock() instanceof ChestBlock)) {
            msg("No chest at " + target.toShortString() + " (not loaded or not a chest).");
            target = null;
            return 0;
        }
        timer = 0;
        queue = null;
        state = State.PATHING;
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(target, 3));
        msg("Going to chest at " + target.toShortString());
        return 1;
    }

    // ------------------------------------------------------------------ state machine

    private void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) { state = State.IDLE; return; }

        switch (state) {
            case PATHING -> {
                if (++timer > PATH_TIMEOUT) { stop("Pathing timed out."); return; }
                double dist = p.getPos().squaredDistanceTo(Vec3d.ofCenter(target));
                boolean pathing = baritone().getPathingBehavior().isPathing();
                if (dist <= 16.0 && !pathing) {   // within 4 blocks and Baritone finished
                    baritone().getPathingBehavior().cancelEverything();
                    timer = 0;
                    state = State.OPENING;
                } else if (!pathing && timer > 40 && dist > 16.0) {
                    stop("Baritone could not reach the chest.");
                }
            }
            case OPENING -> {
                if (timer == 0) {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                }
                if (p.currentScreenHandler != p.playerScreenHandler) {
                    timer = 0;
                    queue = null;
                    state = State.DEPOSITING;
                } else if (++timer > OPEN_TIMEOUT) {
                    stop("Could not open the chest.");
                }
            }
            case DEPOSITING -> {
                ScreenHandler h = p.currentScreenHandler;
                if (h == p.playerScreenHandler) { stop("Chest closed."); return; }
                if (queue == null) {
                    queue = buildPlan(p, h);
                    if (queue.isEmpty()) {
                        p.closeHandledScreen();
                        stop("Nothing to deposit (keep rules / filter / baseline).");
                        return;
                    }
                }
                if (++timer % 2 != 0) return;   // one click every 2 ticks (anti-kick friendly)
                Action a = queue.poll();
                if (a == null) {
                    p.closeHandledScreen();
                    stop("Done loading chest.");
                    return;
                }
                mc.interactionManager.clickSlot(h.syncId, a.slot(), a.button(), a.type(), p);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ deposit planning

    private static String idOf(ItemStack st) {
        return Registries.ITEM.getId(st.getItem()).toString();
    }

    private static String pathOf(ItemStack st) {
        return Registries.ITEM.getId(st.getItem()).getPath();
    }

    /** Amount of this item to keep: -1 = all, Integer.MAX_VALUE = no rule (keep none). */
    private static int keepFor(ItemStack st) {
        String path = pathOf(st);
        for (Map.Entry<String, Integer> e : cfg.keep.entrySet()) {
            String k = e.getKey();
            boolean match = k.equals("@food") ? st.contains(DataComponentTypes.FOOD) : path.contains(k);
            if (match) return e.getValue();
        }
        return 0;   // no rule -> keep nothing
    }

    private ArrayDeque<Action> buildPlan(ClientPlayerEntity p, ScreenHandler h) {
        ArrayDeque<Action> plan = new ArrayDeque<>();

        // player slots by inventory index (0-8 hotbar, 9-35 main), and chest slots
        Slot[] mine = new Slot[36];
        java.util.List<Slot> chest = new java.util.ArrayList<>();
        for (Slot s : h.slots) {
            if (s.inventory == p.getInventory()) {
                if (s.getIndex() >= 0 && s.getIndex() < 36) mine[s.getIndex()] = s;
            } else {
                chest.add(s);
            }
        }

        // total per item over the WHOLE inventory (hotbar counts towards keep limits)
        Map<String, Integer> total = new HashMap<>();
        for (Slot s : mine) {
            if (s != null && !s.getStack().isEmpty()) total.merge(idOf(s.getStack()), s.getStack().getCount(), Integer::sum);
        }

        // quota = how many of each item should leave the inventory
        Map<String, Integer> quota = new HashMap<>();
        for (Slot s : mine) {
            if (s == null || s.getStack().isEmpty()) continue;
            ItemStack st = s.getStack();
            String id = idOf(st);
            if (quota.containsKey(id)) continue;
            int keep = keepFor(st);
            int q = keep < 0 ? 0 : Math.max(0, total.get(id) - keep);
            if (minedOnly) q = Math.min(q, Math.max(0, total.get(id) - baseline.getOrDefault(id, 0)));
            if (!filter.isEmpty() && !pathOf(st).contains(filter)) q = 0;
            quota.put(id, q);
        }

        // order: main inventory first, hotbar last (and only if enabled)
        int[] order = new int[cfg.includeHotbar ? 36 : 27];
        for (int i = 0; i < 27; i++) order[i] = 9 + i;
        if (cfg.includeHotbar) for (int i = 0; i < 9; i++) order[27 + i] = i;

        Set<Integer> usedEmpty = new HashSet<>();
        for (int idx : order) {
            Slot s = mine[idx];
            if (s == null || s.getStack().isEmpty()) continue;
            ItemStack st = s.getStack();
            String id = idOf(st);
            int q = quota.getOrDefault(id, 0);
            if (q <= 0) continue;
            int c = st.getCount();

            if (q >= c) {                        // whole stack leaves
                plan.add(new Action(s.id, 0, SlotActionType.QUICK_MOVE));
                quota.put(id, q - c);
            } else {                             // split: keep (c - q) here, deposit q
                Slot dest = findChestTarget(chest, st, q, usedEmpty);
                if (dest == null) continue;      // chest has no room for this item
                plan.add(new Action(s.id, 0, SlotActionType.PICKUP));          // pick up all
                for (int i = 0; i < c - q; i++) plan.add(new Action(s.id, 1, SlotActionType.PICKUP)); // put 1 back each
                plan.add(new Action(dest.id, 0, SlotActionType.PICKUP));       // drop remainder in chest
                quota.put(id, 0);
            }
        }
        return plan;
    }

    private Slot findChestTarget(java.util.List<Slot> chest, ItemStack st, int amount, Set<Integer> usedEmpty) {
        for (Slot s : chest) {   // prefer a matching stack with room
            ItemStack o = s.getStack();
            if (!o.isEmpty() && ItemStack.areItemsAndComponentsEqual(o, st)
                    && o.getMaxCount() - o.getCount() >= amount) return s;
        }
        for (Slot s : chest) {   // otherwise an empty slot
            if (s.getStack().isEmpty() && !usedEmpty.contains(s.id)) {
                usedEmpty.add(s.id);
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private BlockPos findNearestChest(MinecraftClient mc) {
        BlockPos origin = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, SEARCH_RADIUS, SEARCH_RADIUS / 2, SEARCH_RADIUS)) {
            Block b = mc.world.getBlockState(pos).getBlock();
            if (!(b instanceof ChestBlock)) continue;
            if (!(mc.world.getBlockEntity(pos) instanceof ChestBlockEntity)) continue;
            double d = pos.getSquaredDistance(origin);
            if (d < bestDist) { bestDist = d; best = pos.toImmutable(); }
        }
        return best;
    }

    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void stop(String text) {
        if (state == State.PATHING) baritone().getPathingBehavior().cancelEverything();
        state = State.IDLE;
        queue = null;
        msg(text);
    }

    private static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[LoadChest] " + s), false);
    }
}
