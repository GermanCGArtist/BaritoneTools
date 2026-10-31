package dev.survivalkit;

import baritone.api.process.PathingCommand;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import static dev.survivalkit.Config.cfg;

/** Eats when the food bar is low. Holds the use key like a player would, so it works on servers. */
final class EatProcess extends Feature {
    private static final int EAT_TIMEOUT = 120;     // ticks; eating takes ~32
    private static final int FAIL_COOLDOWN = 200;
    private static final float GOLDEN_AT = 8f;      // 4 hearts

    private boolean eating, started;
    private int timer, cooldown, goldenCooldown;
    private int prevSlot;                // hotbar slot to go back to
    private int swappedFrom = -1;        // inventory index the food was swapped in from, -1 = none
    private int swappedTo;

    EatProcess() {
        super("SurvivalKit eat", 20);
    }

    @Override
    boolean busy() {
        return eating;
    }

    @Override
    boolean active() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!cfg.eat || p == null || mc.level == null || mc.gameMode == null) {
            if (eating) abort();
            return false;
        }
        if (eating) return true;
        if (cooldown > 0) { cooldown--; return false; }
        if (goldenCooldown > 0) goldenCooldown--;
        if (p.isSleeping() || Util.guiOpen(p) || p.isUsingItem()) return false;

        // Natural healing needs a full-ish food bar, so being hurt is a reason to eat just like being hungry is.
        boolean hurt = p.getHealth() < p.getMaxHealth() - 0.01f;
        boolean hungry = p.getFoodData().getFoodLevel() <= cfg.eatBelow;
        int slot = -1;
        if (p.getFoodData().needsFood() && (hungry || hurt)) {
            slot = pickFood(p);
        } else if (hurt && cfg.golden && goldenCooldown == 0 && p.getHealth() <= GOLDEN_AT) {
            // food bar is full, so food can't help: a golden apple heals right now
            slot = pickGolden(p);
            if (slot >= 0) goldenCooldown = 20 * 30;
        }
        if (slot < 0) {   // nothing to eat; restocking may fix that
            String stats = "food " + p.getFoodData().getFoodLevel() + "/20, health " + Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth());
            if (p.getFoodData().needsFood() && (hungry || hurt)) {
                Util.warn("nofood", "Need to eat (" + stats + ") but there is no safe food in the inventory.");
            } else if (hurt && p.getHealth() <= 6) {
                Util.warn("lowhealth", "Health is low (" + stats + "), the food bar is full and there is no golden apple - waiting for natural healing.");
            }
            return false;
        }
        begin(mc, p, slot);
        return true;
    }

    /** A plain golden apple (never the enchanted one). */
    private static int pickGolden(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && Util.pathOf(st).equals("golden_apple")) return i;
        }
        return -1;
    }

    /** Best food: the biggest one that does not overshoot the missing hunger, else the smallest one. */
    private static int pickFood(LocalPlayer p) {
        int missing = 20 - p.getFoodData().getFoodLevel();
        int best = -1, bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!Util.isGoodFood(st)) continue;
            int n = Util.nutrition(st);
            int score = n <= missing ? 100 + n : 100 - n;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private void begin(Minecraft mc, LocalPlayer p, int slot) {
        eating = true;
        started = false;
        timer = 0;
        prevSlot = p.getInventory().getSelectedSlot();
        if (slot >= 9) {   // food is in the main inventory: swap it into the held slot, swapped back afterwards
            swappedFrom = slot;
            swappedTo = prevSlot;
            Util.swapIntoHotbar(mc, p, slot, prevSlot);
        } else {
            swappedFrom = -1;
            p.getInventory().setSelectedSlot(slot);
        }
    }

    @Override
    PathingCommand step(boolean calcFailed, boolean isSafeToCancel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!eating || p == null) return defer();

        if (p.isUsingItem()) started = true;
        if ((started && !p.isUsingItem()) || ++timer > EAT_TIMEOUT) {
            if (!started) {
                cooldown = FAIL_COOLDOWN;
                Util.msg("Could not start eating " + p.getInventory().getSelectedItem().getHoverName().getString()
                        + " (the game never reported it as being used). Trying again in 10 s.");
            }
            finish(mc, p);
            return pause();
        }
        // Vanilla drops the item the moment the use key is up, so hold it (the real key plus Baritone's override).
        mc.options.keyUse.setDown(true);
        Util.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        // Start eating with the same call a right-click makes. Waiting 2 ticks lets the hotbar swap/selection
        // reach the server first; if it didn't take, try again every half second.
        if (!started && timer >= 2 && (timer - 2) % 10 == 0) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
        return pause();
    }

    private void finish(Minecraft mc, LocalPlayer p) {
        mc.options.keyUse.setDown(false);
        Util.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        if (swappedFrom >= 0) {
            Util.swapIntoHotbar(mc, p, swappedFrom, swappedTo);
            swappedFrom = -1;
        }
        p.getInventory().setSelectedSlot(prevSlot);
        eating = false;
    }

    private void abort() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null) finish(mc, mc.player);
        eating = false;
    }

    @Override
    public void onLostControl() {   // e.g. #stop
        if (eating) abort();
    }
}
