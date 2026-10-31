package dev.survivalkit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static dev.survivalkit.Config.cfg;

/**
 * Baritone addon that keeps a long-running task alive:
 *   eat      - eats when hungry
 *   defence  - fights hostile mobs that get close
 *   sleep    - sleeps through the night in a saved bed
 *   restock  - refills food/tools/torches/... from a supply chest
 * Every feature is a temporary Baritone process, so the running task (#mine, #farm, #build, #sel ca, ...) is paused
 * and then continues by itself.
 */
public class SurvivalKitMod implements ClientModInitializer {

    static final EatProcess eat = new EatProcess();
    static final DefenceProcess defence = new DefenceProcess();
    static final SleepProcess sleep = new SleepProcess();
    static final RestockProcess restock = new RestockProcess();

    private boolean registered = false;

    @Override
    public void onInitializeClient() {
        Config.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("survival")
                        .executes(ctx -> status())
                        .then(ClientCommands.literal("status").executes(ctx -> status()))

                        .then(toggle("eat", v -> cfg.eat = v, "Auto-eat"))
                        .then(toggle("golden", v -> cfg.golden = v, "Golden apple when low on health"))
                        .then(toggle("background", v -> cfg.background = v, "Keep running when the window loses focus"))
                        .then(toggle("debug", v -> Util.debugOn = v, "Debug output"))
                        .then(toggle("defence", v -> cfg.defence = v, "Defence"))
                        .then(toggle("sleep", v -> cfg.sleep = v, "Auto-sleep")
                                .then(ClientCommands.literal("now").executes(ctx -> {
                                    sleep.startNow();
                                    return 1;
                                })))
                        .then(toggle("restock", v -> cfg.restock = v, "Auto-restock")
                                .then(ClientCommands.literal("now").executes(ctx -> {
                                    restock.startNow();
                                    return 1;
                                })))

                        .then(ClientCommands.literal("eatbelow")
                                .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 19)).executes(ctx -> {
                                    cfg.eatBelow = IntegerArgumentType.getInteger(ctx, "level");
                                    Config.save();
                                    Util.msg("Eating when the food bar is at " + cfg.eatBelow + "/20 or lower.");
                                    return 1;
                                })))

                        .then(placeCmd("bed", "bed", b -> b instanceof BedBlock, v -> cfg.bed = v))
                        .then(placeCmd("supply", "supply chest", b -> b instanceof ChestBlock || b instanceof BarrelBlock, v -> cfg.supply = v))

                        .then(ClientCommands.literal("want")
                                .then(ClientCommands.literal("list").executes(ctx -> {
                                    if (cfg.want.isEmpty()) Util.msg("The want-list is empty.");
                                    cfg.want.forEach((k, v) -> Util.msg("want " + k + ": " + v));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("remove")
                                        .then(ClientCommands.argument("item", StringArgumentType.string()).executes(ctx -> {
                                            String k = ruleKey(StringArgumentType.getString(ctx, "item"));
                                            if (cfg.want.remove(k) != null) {
                                                Config.save();
                                                Util.msg("Removed: " + k);
                                            } else {
                                                Util.msg("No such rule: " + k);
                                            }
                                            return 1;
                                        })))
                                .then(ClientCommands.argument("item", StringArgumentType.string())
                                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 2304)).executes(ctx -> {
                                            String k = ruleKey(StringArgumentType.getString(ctx, "item"));
                                            int n = IntegerArgumentType.getInteger(ctx, "count");
                                            cfg.want.put(k, n);
                                            Config.save();
                                            Util.msg("Carry " + n + " x " + k + " (restocks when below half).");
                                            return 1;
                                        }))))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.player == null) return;
        if (!registered) {
            var control = Util.baritone().getPathingControlManager();
            control.registerProcess(eat);
            control.registerProcess(defence);
            control.registerProcess(sleep);
            control.registerProcess(restock);
            registered = true;
        }
        // Clicking onto another monitor would make vanilla open the pause menu (and pause singleplayer worlds).
        if (cfg.background && mc.options.pauseOnLostFocus) mc.options.pauseOnLostFocus = false;
        if (mc.isPaused() && Util.baritoneBusy()) {
            Util.warn("paused", "The game is paused (pause menu in a singleplayer world), so nothing can happen. Close the menu.");
        }
        try {
            sleep.tick();
            restock.tick();
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("tick-error", "SurvivalKit hit an error (" + t + "). Details are in the game log.");
        }
    }

    // ------------------------------------------------------------------ command helpers

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Consumer<Boolean> set, String what) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("on").executes(ctx -> {
                    set.accept(true);
                    Config.save();
                    Util.msg(what + " on.");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    set.accept(false);
                    Config.save();
                    Util.msg(what + " off.");
                    return 1;
                }));
    }

    /** `<name> set` (block you look at), `<name> set x y z`, `<name> clear`. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> placeCmd(
            String name, String label, Predicate<Block> valid, Consumer<int[]> set) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("set")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.level != null && mc.hitResult instanceof BlockHitResult hit
                                    && valid.test(mc.level.getBlockState(hit.getBlockPos()).getBlock())) {
                                save(set, label, hit.getBlockPos());
                                return 1;
                            }
                            Util.msg("Look at the " + label + " first (or use /survival " + name + " set <x> <y> <z>).");
                            return 0;
                        })
                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    save(set, label, posOf(ctx));
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    set.accept(null);
                    Config.save();
                    Util.msg(label + " cleared.");
                    return 1;
                }));
    }

    private static void save(Consumer<int[]> set, String label, BlockPos p) {
        set.accept(new int[]{p.getX(), p.getY(), p.getZ()});
        Config.save();
        Util.msg(label + " saved: " + p.toShortString());
    }

    /** Rule name as typed: `food` is a shortcut for `@food` (the @ can only be typed inside quotes: "@food"). */
    private static String ruleKey(String typed) {
        String k = typed.toLowerCase();
        return k.equals("food") ? "@food" : k;
    }

    private static BlockPos posOf(CommandContext<?> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static int status() {
        Util.msg("eat " + onOff(cfg.eat) + " (when hurt, or food bar at " + cfg.eatBelow + "/20 or lower; golden apple "
                + onOff(cfg.golden) + "), defence " + onOff(cfg.defence)
                + ", sleep " + onOff(cfg.sleep) + ", restock " + onOff(cfg.restock));
        Util.msg("bed: " + (cfg.bed == null ? "not set" : Util.pos(cfg.bed).toShortString())
                + ", supply chest: " + (cfg.supply == null ? "not set" : Util.pos(cfg.supply).toShortString()));
        Util.msg("want-list: " + (cfg.want.isEmpty() ? "empty" : cfg.want));
        return 1;
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
