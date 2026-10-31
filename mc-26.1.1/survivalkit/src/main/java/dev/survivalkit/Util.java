package dev.survivalkit;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Util {
    private Util() {}

    /** Item ids (or parts) that are technically food but should never be eaten automatically. */
    private static final List<String> BAD_FOOD = List.of(
            "rotten_flesh", "spider_eye", "pufferfish", "poisonous_potato", "chorus_fruit",
            "raw_chicken", "golden_apple", "suspicious_stew", "ominous_bottle");

    static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[SurvivalKit] " + s));
    }

    /** True while any Baritone task other than ours runs (#mine, #farm, #build, #sel ca, #goto, ...). */
    static boolean baritoneBusy() {
        IBaritone b = baritone();
        return b.getMineProcess().isActive() || b.getBuilderProcess().isActive() || b.getFarmProcess().isActive()
                || b.getExploreProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getFollowProcess().isActive() || b.getCustomGoalProcess().isActive();
    }

    /**
     * A chest/furnace/... menu is open, or the player is dead. The player's own inventory, the pause menu and chat do
     * NOT count: everything here works through direct game calls, so the bot keeps going with those open (for example
     * when the game window sits on a second monitor and the pause menu popped up).
     */
    static boolean guiOpen(LocalPlayer p) {
        return p.containerMenu != p.inventoryMenu || !p.isAlive();
    }

    private static final Map<String, Long> lastWarn = new HashMap<>();

    /** Tells the player about a problem in chat, at most once a minute per kind. */
    static void warn(String key, String text) {
        long t = System.currentTimeMillis();
        Long last = lastWarn.get(key);
        if (last != null && t - last < 60_000) return;
        lastWarn.put(key, t);
        msg("PROBLEM: " + text);
    }

    static String pathOf(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
    }

    static boolean isFood(ItemStack st) {
        return !st.isEmpty() && st.has(DataComponents.FOOD);
    }

    /** Food that is safe to eat automatically. */
    static boolean isGoodFood(ItemStack st) {
        if (!isFood(st)) return false;
        String path = pathOf(st);
        for (String bad : BAD_FOOD) {
            if (path.contains(bad)) return false;
        }
        return true;
    }

    static int nutrition(ItemStack st) {
        FoodProperties f = st.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    /**
     * Worn-out tools do not count as stock. With Baritone's item saver on (#set itemSaver true) this is exactly the
     * moment Baritone stops using the tool: damage + itemSaverThreshold >= max damage. Without it: over 80% damage.
     */
    static boolean nearlyBroken(ItemStack st) {
        if (!st.isDamageableItem()) return false;
        var settings = BaritoneAPI.getSettings();
        if (settings.itemSaver.value) {
            return st.getMaxDamage() > 1 && st.getDamageValue() + settings.itemSaverThreshold.value >= st.getMaxDamage();
        }
        return st.getDamageValue() > st.getMaxDamage() * 0.8;
    }

    /** How many worn-out tools matching the rule the player carries. */
    static int wornCount(LocalPlayer p, String key) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (matches(st, key) && st.isDamageableItem() && nearlyBroken(st)) n++;
        }
        return n;
    }

    /** Whether the player carries a worn-out tool matching the rule (it needs replacing even if enough are left). */
    static boolean hasWorn(LocalPlayer p, String key) {
        return wornCount(p, key) > 0;
    }

    static boolean debugOn = false;

    static void debug(String s) {
        if (debugOn) msg("[debug] " + s);
    }

    /** Whether the stack matches a rule key: an item id fragment, or @food. */
    static boolean matches(ItemStack st, String key) {
        if (st.isEmpty()) return false;
        return key.equals("@food") ? isGoodFood(st) : pathOf(st).contains(key);
    }

    /** How many usable items matching the rule the player carries (main inventory + hotbar). */
    static int countHave(LocalPlayer p, String key) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (matches(st, key) && !nearlyBroken(st)) n += st.getCount();
        }
        return n;
    }

    /** Container-menu slot index (in the player's own inventory screen) for a player inventory index 0-35. */
    static int menuSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    /** Swaps an inventory item into the given hotbar slot (0-8) using the player's inventory menu. */
    static void swapIntoHotbar(Minecraft mc, LocalPlayer p, int invIndex, int hotbar) {
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot(invIndex), hotbar, ContainerInput.SWAP, p);
    }

    static BlockPos pos(int[] a) {
        return a == null ? null : new BlockPos(a[0], a[1], a[2]);
    }
}
