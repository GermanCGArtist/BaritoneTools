package dev.baritonequeue;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import static dev.baritonequeue.Config.cfg;

/**
 * Baritone addon: a list of Baritone commands that run one after another.
 * Edit it on screen (key J, changeable in the controls) or with /queue add ...; start it with /queue run.
 */
public class QueueMod implements ClientModInitializer {
    static final TaskRunner runner = new TaskRunner();
    static KeyMapping open;
    private boolean registered = false;
    private int noPlayer;

    @Override
    public void onInitializeClient() {
        Config.load();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("baritonequeue", "main"));
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baritonequeue.open", 74 /* GLFW_KEY_J */, category));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("queue")
                        .executes(ctx -> list())
                        .then(ClientCommands.literal("open").executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new QueueScreen()));   // after the chat screen has closed
                            return 1;
                        }))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("command", StringArgumentType.greedyString()).executes(ctx -> {
                                    String c = StringArgumentType.getString(ctx, "command").trim();
                                    if (c.isEmpty()) return 0;
                                    cfg.script.add(c);
                                    Config.save();
                                    Util.msg("Added line " + cfg.script.size() + ": " + c + (runner.running() ? "" : "   (/queue run starts it)"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("list").executes(ctx -> list()))
                        .then(ClientCommands.literal("run").executes(ctx -> { runner.start(); return 1; }))
                        .then(ClientCommands.literal("start").executes(ctx -> { runner.start(); return 1; }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            if (runner.running()) runner.halt("Queue stopped. /queue run starts it again from the top.");
                            else Util.msg("The queue is not running.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("skip").executes(ctx -> { runner.skip(); return 1; }))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("number", IntegerArgumentType.integer(1)).executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "number");
                                    if (n > cfg.script.size()) { Util.msg("There is no line " + n + "."); return 0; }
                                    Util.msg("Removed: " + cfg.script.remove(n - 1));
                                    Config.save();
                                    return 1;
                                })))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            cfg.script.clear();
                            Config.save();
                            Util.msg("Script cleared.");
                            return 1;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        // The same display on the game screen while the queue runs (hidden with F1, and while the editor is open).
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("baritonequeue", "progress"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!runner.running() || mc.screen instanceof QueueScreen || mc.options.hideGui) return;
            int w = Math.min(220, mc.getWindow().getGuiScaledWidth() - 20);
            ProgressView.draw(g, mc.font, (mc.getWindow().getGuiScaledWidth() - w) / 2, 6, w);
        });
    }

    private static int list() {
        if (cfg.script.isEmpty()) Util.msg("The script is empty. Open the editor (key J) or /queue add <command>.");
        for (int i = 0; i < cfg.script.size(); i++) Util.msg((i + 1) + ". " + cfg.script.get(i));
        Util.msg(runner.status());
        return 1;
    }

    private void tick(Minecraft mc) {
        if (mc.player == null) {
            // a dropped connection that SurvivalKit reconnects: keep the step and carry on afterwards
            if (runner.running() && (System.getProperty("survivalkit.recovering") != null || ++noPlayer < 60)) runner.holdForReconnect();
            else runner.reset();
            return;
        }
        noPlayer = 0;
        if (!registered) {
            PauseWatch.register(Util.baritone());
            registered = true;
        }
        while (open.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new QueueScreen());
        }
        if (PauseWatch.paused) return;   // #pause: the queue holds still
        try {
            runner.tick();
        } catch (Throwable t) {
            t.printStackTrace();
            Util.warn("tick-error", "Queue hit an error (" + t + "). Details are in the game log.");
            runner.reset();
        }
    }
}
