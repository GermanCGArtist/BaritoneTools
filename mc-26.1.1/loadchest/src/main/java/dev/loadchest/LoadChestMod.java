package dev.loadchest;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.BlockInteractEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * While Baritone is mining:
 *   - auto mode    (/loadchest auto on   -> inventory full: go deposit, then mining resumes)
 *   - pickup mode  (/loadchest pickup on -> collect the items that drop from blocks Baritone breaks)
 * Both run as temporary Baritone processes with a higher priority than #mine, so mining is paused, not cancelled.
 */
public class LoadChestMod implements ClientModInitializer {

    private enum State { IDLE, PATHING, OPENING, DEPOSITING }

    private record Action(int slot, int button, ContainerInput type) {}

    /** Persisted settings (config/loadchest.json). keep value -1 = keep ALL matching items. */
    private static class Config {
        boolean includeHotbar = false;
        boolean auto = false;
        boolean pickup = false;
        boolean pickupAll = false;   // false: only items from blocks Baritone broke; true: every item nearby
        int[] chest = null;
        Map<String, Integer> keep = new LinkedHashMap<>();
    }

    private static final int SEARCH_RADIUS = 48;
    private static final int PICKUP_RADIUS = 16;
    private static final int PATH_TIMEOUT = 20 * 180;  // 3 min (auto trips can be long)
    private static final int OPEN_TIMEOUT = 20 * 5;    // 5 s
    private static final int PICKUP_TIMEOUT = 20 * 6;  // give up on one item after 6 s
    private static final int PICKUP_BATCH = 8;         // start collecting once this many drops are lying around
    private static final int PICKUP_MIN_AGE = 20 * 3;  // ...that are at least 3 s old (the miner may pass them anyway)
    private static final int PICKUP_STALE = 20 * 20;   // or when a single drop has been left for 20 s

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
    private boolean autoRun = false;   // current trip was started by auto mode
    private int autoCheck = 0;
    private boolean registered = false;
    private final DepositProcess depositProcess = new DepositProcess();
    private final PickupProcess pickupProcess = new PickupProcess();

    // ------------------------------------------------------------------ init

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("loadchest")
                        .executes(ctx -> start("", null, false))

                        // ---- chest selection
                        .then(ClientCommands.literal("set")
                                .executes(ctx -> setLookedAt())
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            BlockPos p = posOf(ctx);
                                            cfg.chest = new int[]{p.getX(), p.getY(), p.getZ()};
                                            saveConfig();
                                            msg("Chest saved: " + p.toShortString());
                                            return 1;
                                        })))))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            cfg.chest = null;
                            saveConfig();
                            msg("Saved chest cleared (using nearest chest).");
                            return 1;
                        }))
                        .then(ClientCommands.literal("nearest")
                                .executes(ctx -> start("", findNearestChest(Minecraft.getInstance()), false))
                                .then(ClientCommands.argument("filter", StringArgumentType.word())
                                        .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"),
                                                findNearestChest(Minecraft.getInstance()), false))))
                        .then(ClientCommands.literal("at")
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> start("", posOf(ctx), false))
                                        .then(ClientCommands.argument("filter", StringArgumentType.word())
                                                .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"),
                                                        posOf(ctx), false)))))))

                        // ---- mined-only mode
                        .then(ClientCommands.literal("baseline").executes(ctx -> {
                            takeBaseline();
                            return 1;
                        }))
                        .then(ClientCommands.literal("mined")
                                .executes(ctx -> start("", null, true))
                                .then(ClientCommands.argument("filter", StringArgumentType.word())
                                        .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"), null, true))))

                        // ---- hotbar toggle
                        .then(ClientCommands.literal("hotbar")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    cfg.includeHotbar = true;
                                    saveConfig();
                                    msg("Hotbar will be deposited too.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.includeHotbar = false;
                                    saveConfig();
                                    msg("Hotbar is left alone.");
                                    return 1;
                                })))

                        // ---- auto empty / pickup while mining
                        .then(ClientCommands.literal("auto")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    cfg.auto = true;
                                    saveConfig();
                                    msg("Auto-empty on: when the inventory is full during any Baritone task (#mine, #farm, #build, #sel ...), it deposits into the chest and the task continues.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.auto = false;
                                    saveConfig();
                                    msg("Auto-empty off.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("debug")
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    debugOn = true;
                                    msg("Debug on: pickup decisions are shown in chat.");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    debugOn = false;
                                    msg("Debug off.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("pickup")
                                .then(ClientCommands.literal("on").executes(ctx -> setPickup(true, false)))
                                .then(ClientCommands.literal("mined").executes(ctx -> setPickup(true, false)))
                                .then(ClientCommands.literal("all").executes(ctx -> setPickup(true, true)))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    cfg.pickup = false;
                                    saveConfig();
                                    msg("Pickup off.");
                                    return 1;
                                })))

                        // ---- keep rules
                        .then(ClientCommands.literal("keep")
                                .then(ClientCommands.literal("list").executes(ctx -> {
                                    if (cfg.keep.isEmpty()) msg("No keep rules.");
                                    cfg.keep.forEach((k, v) -> msg("keep " + k + ": " + (v < 0 ? "all" : v)));
                                    msg("Hotbar: " + (cfg.includeHotbar ? "deposited" : "left alone")
                                            + ", auto-empty: " + (cfg.auto ? "on" : "off")
                                            + ", pickup: " + (!cfg.pickup ? "off" : cfg.pickupAll ? "all items" : "own mined items only"));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("item", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String k = ruleKey(StringArgumentType.getString(ctx, "item"));
                                                    if (cfg.keep.remove(k) != null) {
                                                        saveConfig();
                                                        msg("Removed keep rule: " + k);
                                                    } else msg("No such rule: " + k);
                                                    return 1;
                                                })))
                                .then(ClientCommands.argument("item", StringArgumentType.string())
                                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(-1))
                                                .executes(ctx -> {
                                                    String k = ruleKey(StringArgumentType.getString(ctx, "item"));
                                                    int n = IntegerArgumentType.getInteger(ctx, "count");
                                                    cfg.keep.put(k, n);
                                                    saveConfig();
                                                    msg("Keep " + (n < 0 ? "all" : n) + " x " + k);
                                                    return 1;
                                                }))))

                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            stop("Stopped.");
                            return 1;
                        }))

                        // ---- /loadchest <filter>
                        .then(ClientCommands.argument("filter", StringArgumentType.word())
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

    /** /loadchest pickup on|mined|all: collect only items from blocks Baritone broke, or every item nearby. */
    private int setPickup(boolean on, boolean all) {
        cfg.pickup = on;
        cfg.pickupAll = all;
        saveConfig();
        if (all) {
            msg("Pickup: ALL items within " + PICKUP_RADIUS + " blocks, including mob drops and items in item-transport lines.");
        } else {
            msg("Pickup: only items dropped by blocks Baritone breaks (within " + PICKUP_RADIUS + " blocks); mob drops and other items are left alone.");
        }
        return 1;
    }

    /** Rule name as typed: `food` is a shortcut for `@food` (the @ can only be typed inside quotes: "@food"). */
    private static String ruleKey(String typed) {
        String k = typed.toLowerCase();
        return k.equals("food") ? "@food" : k;
    }

    private BlockPos posOf(CommandContext<?> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private int setLookedAt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        if (mc.hitResult instanceof BlockHitResult hit
                && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof ChestBlock) {
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        baseline.clear();
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (!st.isEmpty()) baseline.merge(idOf(st), st.getCount(), Integer::sum);
        }
        baselineSet = true;
        msg("Baseline saved. /loadchest mined will deposit only items gained after this point.");
    }

    private int start(String arg, BlockPos explicit, boolean mined) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

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
        if (!(mc.level.getBlockState(target).getBlock() instanceof ChestBlock)) {
            msg("No chest at " + target.toShortString() + " (not loaded or not a chest).");
            target = null;
            return 0;
        }
        timer = 0;
        queue = null;
        state = State.PATHING;   // DepositProcess now takes control of Baritone
        msg("Going to chest at " + target.toShortString());
        return 1;
    }

    // ------------------------------------------------------------------ own drops
    //
    // Pickup must only collect items that dropped from blocks Baritone itself broke - not mob drops, not items another
    // player dropped, not items riding a water/ice transport line. So: remember where blocks vanished while Baritone
    // was breaking something, and claim item entities that appear right there.

    private record Mined(BlockPos pos, long tick) {}

    private final List<Mined> minedBlocks = new ArrayList<>();
    private final Set<Integer> ownedDrops = new HashSet<>();
    private long gameTick;
    private long lastMiningTick = -1000;

    private void noteBrokenBlocks(BlockChangeEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || gameTick - lastMiningTick > 30) return;   // nothing was being broken just now
        for (var change : event.getBlocks()) {
            BlockState state = change.second();
            if (!state.isAir() && state.getFluidState().isEmpty()) continue;   // not removed
            BlockPos pos = change.first();
            if (pos.distSqr(mc.player.blockPosition()) > 8 * 8) continue;      // out of reach, so not ours
            minedBlocks.add(new Mined(pos.immutable(), gameTick));
        }
    }

    private void trackOwnDrops(Minecraft mc, LocalPlayer p) {
        gameTick++;
        if (baritone().getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT) || mc.gameMode.isDestroying()) {
            lastMiningTick = gameTick;
        }
        if (gameTick % 200 == 0) ownedDrops.removeIf(id -> mc.level.getEntity(id) == null);
        minedBlocks.removeIf(m -> gameTick - m.tick() > 60);
        if (minedBlocks.isEmpty()) return;

        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(12))) {
            if (e.tickCount > 30 || ownedDrops.contains(e.getId())) continue;   // only brand-new items can be fresh drops
            for (Mined m : minedBlocks) {
                double dx = e.getX() - (m.pos().getX() + 0.5), dz = e.getZ() - (m.pos().getZ() + 0.5);
                double dy = e.getY() - (m.pos().getY() + 0.5);
                // spawns at the block, may already have fallen a few blocks if the block below was open
                if (dx * dx + dz * dz <= 1.7 && dy >= -4 && dy <= 1.5) {
                    ownedDrops.add(e.getId());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ state machine

    private void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) { state = State.IDLE; return; }

        if (!registered) {
            baritone().getPathingControlManager().registerProcess(depositProcess);
            baritone().getPathingControlManager().registerProcess(pickupProcess);
            baritone().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onBlockChange(BlockChangeEvent event) {
                    noteBrokenBlocks(event);
                }

                @Override
                public void onBlockInteract(BlockInteractEvent event) {
                    if (event.getType() == BlockInteractEvent.Type.START_BREAK) lastMiningTick = gameTick;
                }
            });
            registered = true;
        }
        trackOwnDrops(mc, p);

        if (state == State.IDLE) {
            // auto mode: check once a second whether the inventory filled up during #mine
            if (cfg.auto && ++autoCheck % 20 == 0 && baritoneBusy() && inventoryFull(p)) {
                msg("Inventory full - emptying into the chest, mining continues afterwards.");
                if (start("", null, false) == 1) {
                    autoRun = true;
                } else {
                    cfg.auto = false;
                    saveConfig();
                    msg("Auto-empty turned off (no chest). Set one with /loadchest set, then /loadchest auto on.");
                }
            }
            return;
        }

        switch (state) {
            case PATHING -> {
                if (++timer > PATH_TIMEOUT) { stop("Pathing timed out."); return; }
                if (new GoalNear(target, 3).isInGoal(p.blockPosition())) {
                    timer = 0;
                    state = State.OPENING;   // DepositProcess now pauses Baritone
                }
            }
            case OPENING -> {
                if (timer == 4) {   // give the player a few ticks to stop moving

                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
                if (p.containerMenu != p.inventoryMenu) {
                    timer = 0;
                    queue = null;
                    state = State.DEPOSITING;
                } else if (++timer > OPEN_TIMEOUT) {
                    stop("Could not open the chest.");
                }
            }
            case DEPOSITING -> {
                AbstractContainerMenu h = p.containerMenu;
                if (h == p.inventoryMenu) { stop("Chest closed."); return; }
                if (queue == null) {
                    queue = buildPlan(p, h);
                    if (queue.isEmpty()) {
                        finishDeposit(p, "Nothing to deposit (keep rules / filter / baseline).");
                        return;
                    }
                }
                if (++timer % 2 != 0) return;   // one click every 2 ticks (anti-kick friendly)
                Action a = queue.poll();
                if (a == null) {
                    finishDeposit(p, "Done loading chest.");
                    return;
                }
                mc.gameMode.handleContainerInput(h.containerId, a.slot(), a.button(), a.type(), p);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ deposit planning

    private static String idOf(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    private static String pathOf(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
    }

    /** Amount of this item to keep: -1 = all, Integer.MAX_VALUE = no rule (keep none). */
    private static int keepFor(ItemStack st) {
        String path = pathOf(st);
        for (Map.Entry<String, Integer> e : cfg.keep.entrySet()) {
            String k = e.getKey();
            boolean match = k.equals("@food") ? st.has(DataComponents.FOOD) : path.contains(k);
            if (match) return e.getValue();
        }
        return 0;   // no rule -> keep nothing
    }

    private ArrayDeque<Action> buildPlan(LocalPlayer p, AbstractContainerMenu h) {
        ArrayDeque<Action> plan = new ArrayDeque<>();

        // player slots by inventory index (0-8 hotbar, 9-35 main), and chest slots
        Slot[] mine = new Slot[36];
        java.util.List<Slot> chest = new java.util.ArrayList<>();
        for (Slot s : h.slots) {
            if (s.container == p.getInventory()) {
                if (s.getContainerSlot() >= 0 && s.getContainerSlot() < 36) mine[s.getContainerSlot()] = s;
            } else {
                chest.add(s);
            }
        }

        // total per item over the WHOLE inventory (hotbar counts towards keep limits)
        Map<String, Integer> total = new HashMap<>();
        for (Slot s : mine) {
            if (s != null && !s.getItem().isEmpty()) total.merge(idOf(s.getItem()), s.getItem().getCount(), Integer::sum);
        }

        // quota = how many of each item should leave the inventory
        Map<String, Integer> quota = new HashMap<>();
        for (Slot s : mine) {
            if (s == null || s.getItem().isEmpty()) continue;
            ItemStack st = s.getItem();
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
            if (s == null || s.getItem().isEmpty()) continue;
            ItemStack st = s.getItem();
            String id = idOf(st);
            int q = quota.getOrDefault(id, 0);
            if (q <= 0) continue;
            int c = st.getCount();

            if (q >= c) {                        // whole stack leaves
                plan.add(new Action(s.index, 0, ContainerInput.QUICK_MOVE));
                quota.put(id, q - c);
            } else {                             // split: keep (c - q) here, deposit q
                Slot dest = findChestTarget(chest, st, q, usedEmpty);
                if (dest == null) continue;      // chest has no room for this item
                plan.add(new Action(s.index, 0, ContainerInput.PICKUP));          // pick up all
                for (int i = 0; i < c - q; i++) plan.add(new Action(s.index, 1, ContainerInput.PICKUP)); // put 1 back each
                plan.add(new Action(dest.index, 0, ContainerInput.PICKUP));       // drop remainder in chest
                quota.put(id, 0);
            }
        }
        return plan;
    }

    private Slot findChestTarget(java.util.List<Slot> chest, ItemStack st, int amount, Set<Integer> usedEmpty) {
        for (Slot s : chest) {   // prefer a matching stack with room
            ItemStack o = s.getItem();
            if (!o.isEmpty() && ItemStack.isSameItemSameComponents(o, st)
                    && o.getMaxStackSize() - o.getCount() >= amount) return s;
        }
        for (Slot s : chest) {   // otherwise an empty slot
            if (s.getItem().isEmpty() && !usedEmpty.contains(s.index)) {
                usedEmpty.add(s.index);
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private BlockPos findNearestChest(Minecraft mc) {
        BlockPos origin = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.withinManhattan(origin, SEARCH_RADIUS, SEARCH_RADIUS / 2, SEARCH_RADIUS)) {
            Block b = mc.level.getBlockState(pos).getBlock();
            if (!(b instanceof ChestBlock)) continue;
            if (!(mc.level.getBlockEntity(pos) instanceof ChestBlockEntity)) continue;
            double d = pos.distSqr(origin);
            if (d < bestDist) { bestDist = d; best = pos.immutable(); }
        }
        return best;
    }

    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Ends the chest trip; DepositProcess goes inactive and Baritone hands control back (e.g. to #mine). */
    private void stop(String text) {
        state = State.IDLE;
        queue = null;
        autoRun = false;
        msg(text);
    }

    private void finishDeposit(LocalPlayer p, String text) {
        p.closeContainer();
        boolean wasAuto = autoRun;
        stop(text);
        if (wasAuto && inventoryFull(p)) {   // would loop chest <-> mine forever
            cfg.auto = false;
            saveConfig();
            baritone().getPathingBehavior().cancelEverything();
            msg("Inventory is still full (chest full, or everything is protected by keep rules). "
                    + "Stopped mining and turned auto-empty off.");
        }
    }

    private static boolean inventoryFull(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ Baritone processes

    /** Drives Baritone during a chest trip: path to the chest, then hold still while the chest is used. */
    private class DepositProcess implements IBaritoneProcess {
        @Override
        public boolean isActive() {
            return state != State.IDLE;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            if (state == State.PATHING) {
                if (calcFailed) {
                    stop("Baritone could not reach the chest.");
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                return new PathingCommand(new GoalNear(target, 3), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        @Override
        public boolean isTemporary() {
            return true;   // don't make #mine give up, just pause it
        }

        @Override
        public void onLostControl() {   // e.g. #stop
            if (state != State.IDLE) stop("Cancelled.");
        }

        @Override
        public double priority() {
            return 10;
        }

        @Override
        public String displayName0() {
            return "LoadChest deposit";
        }
    }

    /** True while any Baritone task other than LoadChest runs (#mine, #farm, #build, #sel ca, #goto, #explore, ...). */
    private boolean baritoneBusy() {
        IBaritone b = baritone();
        return b.getMineProcess().isActive() || b.getBuilderProcess().isActive() || b.getFarmProcess().isActive()
                || b.getExploreProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getFollowProcess().isActive() || b.getCustomGoalProcess().isActive();
    }

    /** Whether the main inventory (hotbar included) has room for this stack, counting partial stacks. */
    private static boolean canHold(LocalPlayer p, ItemStack st) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || (ItemStack.isSameItemSameComponents(s, st) && s.getCount() < s.getMaxStackSize())) return true;
        }
        return false;
    }

    /**
     * Satisfied by any block where standing lets the player touch the item. Vanilla picks items up when the player's
     * box grown by (1.0, 0.5, 1.0) overlaps the item, so there is no need to stand in the item's exact block
     * (which can even be solid: a resting item's y can floor into the block below it).
     */
    private static class GoalPickup extends GoalNear {
        private final double ix, iy, iz;

        GoalPickup(ItemEntity e) {
            super(e.blockPosition(), 1);
            ix = e.getX();
            iy = e.getY();
            iz = e.getZ();
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // 1.0 instead of the real 1.425 horizontal reach, and feet at most 0.5 above the item: safe margins
            return Math.abs(ix - (x + 0.5)) <= 1.0 && Math.abs(iz - (z + 0.5)) <= 1.0
                    && iy >= y - 0.5 && iy <= y + 2.0;
        }
    }

    /** While Baritone is busy, walks within reach of the items Baritone itself mined so nothing is left behind. */
    private class PickupProcess implements IBaritoneProcess {
        private ItemEntity item;
        private int itemTimer;    // ticks spent on this item
        private int touchTimer;   // ticks spent within reach without the item being picked up
        private final Set<Integer> ignored = new HashSet<>();   // items we gave up on
        private boolean collecting;   // a batch run is in progress
        private boolean lastBusy;     // Baritone was busy last time we looked
        private int scanTick;
        private int oldCount;         // drops on the ground for at least PICKUP_MIN_AGE (set by findDrop)
        private boolean staleFound;   // a drop has been lying around for at least PICKUP_STALE

        @Override
        public boolean isActive() {
            Minecraft mc = Minecraft.getInstance();
            if (!cfg.pickup || state != State.IDLE || mc.player == null || mc.level == null) {
                item = null;
                collecting = false;
                lastBusy = false;
                return false;
            }
            if (baritoneBusy()) {
                lastBusy = true;
            } else if (lastBusy) {   // the task just ended: one final sweep for whatever is left on the ground
                lastBusy = false;
                collecting = true;
                debug("task finished - final sweep");
            } else if (!collecting) {
                item = null;
                return false;
            }
            if (item == null || !item.isAlive()) {
                // Chasing each drop right after every broken block would interrupt the builder/miner constantly
                // (Baritone drops its current path on every hand-over). So wait until a batch has piled up,
                // then collect everything in one go.
                if (!collecting && ++scanTick % 10 != 0) return false;
                item = findDrop(mc, collecting);
                if (!collecting) {
                    if (item != null && (oldCount >= PICKUP_BATCH || staleFound)) {
                        collecting = true;
                        debug("collecting " + oldCount + " drops");
                    } else {
                        item = null;
                    }
                } else if (item == null) {
                    collecting = false;
                    debug("all drops collected");
                }
                itemTimer = 0;
                touchTimer = 0;
                if (item != null) {
                    debug("target " + item.getItem().getHoverName().getString() + " x" + item.getItem().getCount()
                            + " at " + String.format("%.1f %.1f %.1f", item.getX(), item.getY(), item.getZ())
                            + ", " + String.format("%.1f", Math.sqrt(item.distanceToSqr(mc.player))) + " blocks away");
                }
            }
            return item != null;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            Minecraft mc = Minecraft.getInstance();
            if (item == null || mc.player == null) return new PathingCommand(null, PathingCommandType.DEFER);

            if (mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(item.getBoundingBox())) {
                // vanilla pickup range: the server should take it within the pickup delay (max 40 ticks)
                if (++touchTimer > 50) giveUp("in reach but not picked up (pickup delay / no room)");
            } else {
                touchTimer = 0;
            }
            if (item != null && calcFailed) giveUp("Baritone found no path");
            if (item != null && ++itemTimer > PICKUP_TIMEOUT) giveUp("took too long to reach");
            if (item == null) return new PathingCommand(null, PathingCommandType.DEFER);

            return new PathingCommand(new GoalPickup(item), PathingCommandType.SET_GOAL_AND_PATH);
        }

        private void giveUp(String why) {
            debug("skipping item: " + why);
            ignored.add(item.getId());
            item = null;
        }

        /** Nearest reachable drop; while not collecting only drops older than PICKUP_MIN_AGE count. */
        private ItemEntity findDrop(Minecraft mc, boolean includeFresh) {
            if (ignored.size() > 1000) ignored.clear();
            ItemEntity best = null;
            double bestDist = Double.MAX_VALUE;
            oldCount = 0;
            staleFound = false;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class,
                    mc.player.getBoundingBox().inflate(PICKUP_RADIUS, PICKUP_RADIUS / 2.0, PICKUP_RADIUS))) {
                if (!cfg.pickupAll && !ownedDrops.contains(e.getId())) continue;   // not from a block Baritone broke: leave it alone
                if (!e.isAlive() || ignored.contains(e.getId()) || !canHold(mc.player, e.getItem())) continue;
                boolean old = e.tickCount >= PICKUP_MIN_AGE;
                if (old) {
                    oldCount++;
                    if (e.tickCount >= PICKUP_STALE) staleFound = true;
                }
                if (!old && !includeFresh) continue;
                double d = e.distanceToSqr(mc.player);
                if (d < bestDist) { bestDist = d; best = e; }
            }
            return best;
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {
            item = null;
        }

        @Override
        public double priority() {
            return 9;   // below the chest trip, above #mine
        }

        @Override
        public String displayName0() {
            return "LoadChest pickup";
        }
    }

    private static boolean debugOn = false;

    private static void debug(String s) {
        if (debugOn) msg("[debug] " + s);
    }

    private static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[LoadChest] " + s));
    }
}
