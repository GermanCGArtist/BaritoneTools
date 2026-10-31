package dev.baritoneui;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Opens the Baritone settings screen with a key (default B, changeable in Options > Controls > Baritone UI).
 */
public class BaritoneUiMod implements ClientModInitializer {
    static KeyMapping open;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("baritoneui", "main"));
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baritoneui.open", 66 /* GLFW_KEY_B */, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (open.consumeClick()) {
                if (mc.screen == null && mc.player != null) mc.setScreen(new UiScreen());
            }
        });
    }
}
