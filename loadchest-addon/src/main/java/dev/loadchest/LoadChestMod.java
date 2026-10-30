package dev.loadchest;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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

import java.util.HashSet;
import java.util.Set;

/**
 * Baritone addon: /loadchest [filter]
 * Walks to the nearest chest, opens it and shift-clicks items from the inventory into it.
 *   /loadchest            -> everything in main inventory (not hotbar)
 *   /loadchest all        -> everything incl. hotbar
 *   /loadchest cobblestone -> only items whose id contains "cobblestone"
 */
public class LoadChestMod implements ClientModInitializer {

    private enum State { IDLE, PATHING, OPENING, DEPOSITING }

    private static final int SEARCH_RADIUS = 48;
    private static final int PATH_TIMEOUT = 20 * 60;   // 60 s
    private static final int OPEN_TIMEOUT = 20 * 5;    // 5 s

    private State state = State.IDLE;
    private BlockPos target;
    private String filter = "";
    private boolean includeHotbar = false;
    private int timer = 0;
    private static BlockPos savedChest = null;   // set with /loadchest set
    private final Set<Integer> tried = new HashSet<>();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("loadchest")
                    // /loadchest  -> saved chest (or nearest if none saved)
                    .executes(ctx -> start("", null))
                    // /loadchest set -> save the chest you are looking at
                    .then(ClientCommandManager.literal("set").executes(ctx -> setLookedAt()))
                    // /loadchest set <x> <y> <z>
                    .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        savedChest = new BlockPos(
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z"));
                                        msg("Chest saved: " + savedChest.toShortString());
                                        return 1;
                                    })))))
                    .then(ClientCommandManager.literal("clear").executes(ctx -> {
                        savedChest = null;
                        msg("Saved chest cleared (using nearest chest).");
                        return 1;
                    }))
                    // /loadchest nearest [filter] -> ignore saved chest
                    .then(ClientCommandManager.literal("nearest")
                            .executes(ctx -> start("", findNearestChest(MinecraftClient.getInstance())))
                            .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                                    .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"),
                                            findNearestChest(MinecraftClient.getInstance())))))
                    // /loadchest at <x> <y> <z> [filter] -> one-off chest
                    .then(ClientCommandManager.literal("at")
                            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                    .executes(ctx -> start("", posOf(ctx)))
                                    .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                                            .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"), posOf(ctx))))))))
                    // /loadchest <filter|all>
                    .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                            .executes(ctx -> start(StringArgumentType.getString(ctx, "filter"), null)))
                    .then(ClientCommandManager.literal("stop").executes(ctx -> {
                        stop("Stopped.");
                        return 1;
                    })));
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private BlockPos posOf(com.mojang.brigadier.context.CommandContext<?> ctx) {
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
            savedChest = hit.getBlockPos().toImmutable();
            msg("Chest saved: " + savedChest.toShortString());
            return 1;
        }
        msg("Look at a chest first (or use /loadchest set <x> <y> <z>).");
        return 0;
    }

    private int start(String arg, BlockPos explicit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return 0;

        includeHotbar = arg.equalsIgnoreCase("all");
        filter = includeHotbar ? "" : arg.toLowerCase();

        target = explicit != null ? explicit : (savedChest != null ? savedChest : findNearestChest(mc));
        if (target == null) {
            msg("No chest found within " + SEARCH_RADIUS + " blocks.");
            return 0;
        }
        if (!(mc.world.getBlockState(target).getBlock() instanceof ChestBlock)) {
            msg("No chest at " + target.toShortString() + " (not loaded or not a chest).");
            target = null;
            return 0;
        }
        tried.clear();
        timer = 0;
        state = State.PATHING;
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(target, 3));
        msg("Going to chest at " + target.toShortString());
        return 1;
    }

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
                    state = State.DEPOSITING;
                } else if (++timer > OPEN_TIMEOUT) {
                    stop("Could not open the chest.");
                }
            }
            case DEPOSITING -> {
                if (p.currentScreenHandler == p.playerScreenHandler) { stop("Chest closed."); return; }
                if (++timer % 2 != 0) return;   // one click every 2 ticks (anti-kick friendly)

                ScreenHandler h = p.currentScreenHandler;
                for (Slot s : h.slots) {
                    if (s.inventory != p.getInventory()) continue;          // only player slots
                    int idx = s.getIndex();                                  // 0-8 hotbar, 9-35 main
                    if (!includeHotbar && idx < 9) continue;
                    if (idx > 35) continue;                                  // armor/offhand
                    ItemStack st = s.getStack();
                    if (st.isEmpty() || tried.contains(s.id)) continue;
                    if (!filter.isEmpty() && !Registries.ITEM.getId(st.getItem()).toString().contains(filter)) continue;

                    tried.add(s.id);   // if it is still there afterwards the chest is full for it
                    mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
                    return;
                }
                p.closeHandledScreen();
                stop("Done loading chest.");
            }
            default -> {}
        }
    }

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
        msg(text);
    }

    private void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[LoadChest] " + s), false);
    }
}
