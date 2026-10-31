package dev.survivalkit;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.survivalkit.Config.cfg;

/**
 * Supplies: when something on the want-list (food, torches, tools, ...) runs low, walk to the supply chest, take what
 * is missing and carry on with the task.
 */
final class RestockProcess extends Feature {
    private enum State { IDLE, PATHING, OPENING, TAKING }

    private record Action(int slot, int button, ContainerInput type) {}

    private static final int PATH_TIMEOUT = 20 * 180;
    private static final int OPEN_TIMEOUT = 20 * 5;
    private static final long RETRY_BUSY = 20L * 60;        // a rule the chest could not fill is skipped for 1 min
    private static final long RETRY_IDLE = 20L * 60 * 5;    // ... or 5 min when nothing is running anyway

    private State state = State.IDLE;
    private BlockPos chest;
    private int timer, cooldown, check;
    private long now;
    private ArrayDeque<Action> queue;
    private final Map<String, Long> exhausted = new HashMap<>();
    private final Map<String, Integer> lastWorn = new HashMap<>();
    private final List<String> summary = new ArrayList<>();

    RestockProcess() {
        super("SurvivalKit restock", 12);
    }

    @Override
    boolean busy() {
        return state != State.IDLE;
    }

    private static boolean isStorage(Block b) {
        return b instanceof ChestBlock || b instanceof BarrelBlock;
    }

    @Override
    void tick() {
        now++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (state != State.IDLE || p == null || mc.level == null) return;
        if (cooldown > 0) cooldown--;
        if (!cfg.restock || cfg.want.isEmpty() || ++check % 10 != 0) return;   // 2x per second

        // A tool that just hit the item-saver limit gets a fresh chance even if the chest was empty a minute ago.
        for (String key : cfg.want.keySet()) {
            int worn = Util.wornCount(p, key);
            if (worn > lastWorn.getOrDefault(key, 0)) {
                exhausted.remove(key);
                Util.debug("worn-out " + key + " detected");
            }
            lastWorn.put(key, worn);
        }
        if (cooldown > 0) return;

        List<String> stock = new ArrayList<>(), worn = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cfg.want.entrySet()) {
            String key = e.getKey();
            if (exhausted.getOrDefault(key, 0L) > now) continue;
            int have = Util.countHave(p, key);
            if (have < e.getValue() && Util.hasWorn(p, key)) worn.add(key);   // replace right away
            else if (have * 2 < e.getValue()) stock.add(key);                  // plain stock running low
        }
        if (worn.isEmpty() && stock.isEmpty()) return;
        if (cfg.supply == null) {
            if (!worn.isEmpty() || Util.baritoneBusy()) {
                Util.warn("nosupply", "Need " + String.join(", ", worn.isEmpty() ? stock : worn)
                        + " but no supply chest is set. Look at the chest and run /survival supply set.");
            }
            return;
        }

        // Replacing a worn tool never waits for a running task; topping up plain stock only matters during one.
        if (worn.isEmpty() && !Util.baritoneBusy()) return;
        if (p.isSleeping() || Util.guiOpen(p) || SurvivalKitMod.sleep.busy()) {
            Util.debug("restock waiting (sleeping / screen open / on the way to bed)");
            return;
        }
        if (!worn.isEmpty()) {
            Util.msg("Tool at the item-saver limit (" + String.join(", ", worn) + ") - swapping it at the supply chest.");
        } else {
            Util.msg("Running low on " + String.join(", ", stock) + " - going to the supply chest.");
        }
        start(mc);
    }

    /** /survival restock now */
    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (cfg.supply == null) { Util.msg("No supply chest saved. Look at a chest and run /survival supply set"); return; }
        if (cfg.want.isEmpty()) { Util.msg("The want-list is empty. Add rules with /survival want <item> <count>"); return; }
        if (state != State.IDLE) { Util.msg("Already restocking."); return; }
        exhausted.clear();
        start(mc);
    }

    private void start(Minecraft mc) {
        chest = Util.pos(cfg.supply);
        if (!isStorage(mc.level.getBlockState(chest).getBlock())) {
            Util.msg("No chest at " + chest.toShortString() + " (not loaded, or it was removed). Checking again in 2 minutes.");
            cooldown = 20 * 120;
            return;
        }
        timer = 0;
        queue = null;
        state = State.PATHING;
    }

    @Override
    boolean active() {
        if (state != State.IDLE && Minecraft.getInstance().player == null) state = State.IDLE;
        return state != State.IDLE;
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) { state = State.IDLE; return defer(); }

        switch (state) {
            case PATHING -> {
                if (++timer > PATH_TIMEOUT) return fail("Could not reach the supply chest in time.");
                if (calcFailed) return fail("Baritone found no path to the supply chest.");
                GoalNear goal = new GoalNear(chest, 3);
                if (goal.isInGoal(p.blockPosition())) {
                    state = State.OPENING;
                    timer = 0;
                    return pause();
                }
                return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
            }
            case OPENING -> {
                if (p.containerMenu != p.inventoryMenu) {
                    state = State.TAKING;
                    timer = 0;
                    queue = null;
                    return pause();
                }
                if (timer == 4) {   // let the player come to a stop first
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
                if (++timer > OPEN_TIMEOUT) return fail("Could not open the supply chest.");
                return pause();
            }
            case TAKING -> {
                AbstractContainerMenu h = p.containerMenu;
                if (h == p.inventoryMenu) { state = State.IDLE; cooldown = 100; return pause(); }
                if (queue == null) {
                    if (++timer < 8) return pause();   // on a server the chest contents can arrive a moment after it opens
                    summary.clear();
                    queue = buildPlan(p, h);
                    if (queue.isEmpty()) {
                        p.closeContainer();
                        return done("Nothing to take from the supply chest" + (summary.isEmpty() ? "." : " (" + String.join(", ", summary) + ")."));
                    }
                }
                if (++timer % 2 != 0) return pause();   // one click every 2 ticks
                Action a = queue.poll();
                if (a == null) {
                    p.closeContainer();
                    return done("Restocked: " + String.join(", ", summary) + ".");
                }
                mc.gameMode.handleContainerInput(h.containerId, a.slot(), a.button(), a.type(), p);
                return pause();
            }
            default -> {
                return defer();
            }
        }
    }

    private PathingCommand fail(String why) {
        Util.msg(why + " Trying again in 1 minute.");
        state = State.IDLE;
        cooldown = 20 * 60;
        return pause();
    }

    private PathingCommand done(String text) {
        Util.msg(text);
        state = State.IDLE;
        queue = null;
        cooldown = 40;
        return pause();
    }

    // ------------------------------------------------------------------ plan

    private ArrayDeque<Action> buildPlan(LocalPlayer p, AbstractContainerMenu h) {
        ArrayDeque<Action> plan = new ArrayDeque<>();
        Slot[] mine = new Slot[36];
        List<Slot> supply = new ArrayList<>();
        for (Slot s : h.slots) {
            if (s.container == p.getInventory()) {
                if (s.getContainerSlot() >= 0 && s.getContainerSlot() < 36) mine[s.getContainerSlot()] = s;
            } else {
                supply.add(s);
            }
        }

        int freeSupply = 0;   // empty slots in the supply chest, for handing in worn-out tools
        for (Slot s : supply) {
            if (s.getItem().isEmpty()) freeSupply++;
        }

        Set<Integer> used = new HashSet<>();   // player slots already earmarked by this plan
        for (Map.Entry<String, Integer> rule : cfg.want.entrySet()) {
            String key = rule.getKey();
            int have = 0;
            for (Slot s : mine) {
                if (s != null && Util.matches(s.getItem(), key) && !Util.nearlyBroken(s.getItem())) have += s.getItem().getCount();
            }
            int need = rule.getValue() - have;
            if (need <= 0) continue;

            ArrayDeque<Action> takes = new ArrayDeque<>();
            int got = 0, tools = 0;
            for (Slot s : supply) {
                if (got >= need) break;
                ItemStack st = s.getItem();
                if (!Util.matches(st, key) || Util.nearlyBroken(st)) continue;
                int take = Math.min(st.getCount(), need - got);
                if (take(takes, s, st, take, mine, used)) {
                    got += take;
                    if (st.isDamageableItem()) tools += take;
                }
            }

            // Worn-out tools go back into the chest first (to be repaired). That frees their hotbar slot, and a
            // shift-click from the chest fills the hotbar first - Baritone only picks tools from the hotbar.
            int handedIn = 0;
            for (Slot s : mine) {
                if (handedIn >= tools || freeSupply <= 0) break;
                if (s != null && Util.matches(s.getItem(), key) && s.getItem().isDamageableItem() && Util.nearlyBroken(s.getItem())) {
                    plan.add(new Action(s.index, 0, ContainerInput.QUICK_MOVE));
                    handedIn++;
                    freeSupply--;
                }
            }
            plan.addAll(takes);
            if (got > 0) summary.add("took " + got + "x " + key);
            if (handedIn > 0) summary.add("handed in " + handedIn + " worn " + key);
            if (got < need) {
                Util.warn("short-" + key, "The supply chest is short on " + key + " (got " + got + " of " + need + "). Refill it - I'll look again in a minute.");
                summary.add("chest is short on " + key + " (" + got + "/" + need + ")");
                exhausted.put(key, now + (Util.baritoneBusy() ? RETRY_BUSY : RETRY_IDLE));
            }
        }
        if (!plan.isEmpty()) {
            boolean room = false;
            for (Slot s : mine) {
                if (s != null && s.getItem().isEmpty()) { room = true; break; }
            }
            if (!room) {
                Util.warn("fullinv", "The inventory has no empty slot, some of the restock may not fit. Empty it (e.g. /loadchest).");
                summary.add("inventory is full");
            }
        }
        return plan;
    }

    /** Adds the clicks that move `n` items out of the supply slot. Returns false if there is no room. */
    private static boolean take(ArrayDeque<Action> plan, Slot from, ItemStack st, int n, Slot[] mine, Set<Integer> used) {
        int count = st.getCount();
        if (n >= count) {   // whole stack: shift-click
            plan.add(new Action(from.index, 0, ContainerInput.QUICK_MOVE));
            return true;
        }
        Slot dest = findDest(mine, st, n, used);
        if (dest == null) return false;
        if (n <= count / 2) {   // pick the stack up, drop n one by one, put the rest back
            plan.add(new Action(from.index, 0, ContainerInput.PICKUP));
            for (int i = 0; i < n; i++) plan.add(new Action(dest.index, 1, ContainerInput.PICKUP));
            plan.add(new Action(from.index, 0, ContainerInput.PICKUP));
        } else {                // pick the stack up, give back count-n one by one, drop the rest
            plan.add(new Action(from.index, 0, ContainerInput.PICKUP));
            for (int i = 0; i < count - n; i++) plan.add(new Action(from.index, 1, ContainerInput.PICKUP));
            plan.add(new Action(dest.index, 0, ContainerInput.PICKUP));
        }
        return true;
    }

    /** A partial stack of the same item with room, else an empty slot (main inventory first). */
    private static Slot findDest(Slot[] mine, ItemStack st, int n, Set<Integer> used) {
        for (Slot s : mine) {
            if (s == null || used.contains(s.index)) continue;
            ItemStack o = s.getItem();
            if (!o.isEmpty() && ItemStack.isSameItemSameComponents(o, st) && o.getMaxStackSize() - o.getCount() >= n) {
                used.add(s.index);
                return s;
            }
        }
        for (int i = 9; i < 45; i++) {   // inventory indices 9..35, then hotbar 0..8
            Slot s = mine[i % 36];
            if (s != null && s.getItem().isEmpty() && !used.contains(s.index)) {
                used.add(s.index);
                return s;
            }
        }
        return null;
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (state != State.IDLE) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
        }
        state = State.IDLE;
        queue = null;
    }
}
