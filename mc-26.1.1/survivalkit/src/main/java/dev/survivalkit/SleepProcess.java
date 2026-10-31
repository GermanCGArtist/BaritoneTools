package dev.survivalkit;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static dev.survivalkit.Config.cfg;

/** At night: walk to the saved bed, sleep until morning, then hand control back to the task that was running. */
final class SleepProcess extends Feature {
    private enum State { IDLE, PATHING, ENTERING, SLEEPING }

    private static final int PATH_TIMEOUT = 20 * 90;
    private static final int SLEEP_TIMEOUT = 20 * 60 * 15;
    private static final int MAX_TRIES = 5;

    private State state = State.IDLE;
    private BlockPos bed;
    private int timer, tries, cooldown, check;

    SleepProcess() {
        super("SurvivalKit sleep", 15);
    }

    @Override
    boolean busy() {
        return state != State.IDLE;
    }

    static boolean isNight(Minecraft mc) {
        Level lvl = mc.level;
        return lvl != null && lvl.dimension() == Level.OVERWORLD && (lvl.isDarkOutside() || lvl.isThundering());
    }

    /** Cheap trigger check, once per client tick. */
    @Override
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (state != State.IDLE || p == null || mc.level == null) return;
        if (cooldown > 0) { cooldown--; return; }
        if (!cfg.sleep || ++check % 20 != 0) return;
        if (!Util.baritoneBusy() || !isNight(mc) || p.isSleeping() || Util.guiOpen(p)) return;
        if (cfg.bed == null) {
            Util.warn("nobed", "It is night but no bed is saved. Look at your bed and run /survival bed set (or /survival sleep off).");
            return;
        }
        start(mc);
    }

    /** /survival sleep now */
    void startNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (cfg.bed == null) { Util.msg("No bed saved. Look at a bed and run /survival bed set"); return; }
        if (state != State.IDLE) { Util.msg("Already on the way to bed."); return; }
        if (!isNight(mc)) { Util.msg("It is not night (or you are not in the overworld)."); return; }
        start(mc);
    }

    private void start(Minecraft mc) {
        bed = Util.pos(cfg.bed);
        if (!(mc.level.getBlockState(bed).getBlock() instanceof BedBlock)) {
            Util.msg("No bed at " + bed.toShortString() + " (not loaded, or it was removed). Checking again in 2 minutes.");
            cooldown = 20 * 120;
            return;
        }
        timer = 0;
        tries = 0;
        state = State.PATHING;
        Util.msg("Night: going to bed at " + bed.toShortString());
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
        timer++;

        switch (state) {
            case PATHING -> {
                if (timer > PATH_TIMEOUT) return fail("Could not reach the bed in time.");
                if (calcFailed) return fail("Baritone found no path to the bed.");
                GoalNear goal = new GoalNear(bed, 2);
                if (goal.isInGoal(p.blockPosition())) {
                    state = State.ENTERING;
                    timer = 0;
                    return pause();
                }
                return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
            }
            case ENTERING -> {
                if (p.isSleeping()) {
                    state = State.SLEEPING;
                    timer = 0;
                    Util.msg("Sleeping...");
                    return pause();
                }
                if (timer % 15 == 1) {   // try every 0.75 s, the server answers a tick or two later
                    if (tries++ >= MAX_TRIES) return fail("Could not sleep (monsters nearby, or the server refused).");
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bed), Direction.UP, bed, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
                return pause();
            }
            case SLEEPING -> {
                if (!p.isSleeping() || timer > SLEEP_TIMEOUT) {
                    state = State.IDLE;
                    cooldown = 20 * 10;
                    Util.msg("Awake - carrying on.");
                }
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

    @Override
    public void onLostControl() {   // e.g. #stop
        state = State.IDLE;
    }
}
