package com.framegen.config;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class KeybindingHandler {
    private static KeyMapping toggleFrameGenKey;

    public static void register() {
        toggleFrameGenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.framegen.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleFrameGenKey.consumeClick()) {
                FrameGenConfig config = FrameGenConfig.getInstance();
                boolean newState = config.toggleEnabled();
                config.save();

                if (client.player != null) {
                    Component message = Component.literal("§6[FrameGen]§r Frame Generation: " + 
                            (newState ? "§aENABLED (2x Reprojection)§r" : "§cDISABLED (1x Vanilla)§r"));
                    client.player.sendOverlayMessage(message);
                }
            }
        });
    }

    public static KeyMapping getToggleKey() {
        return toggleFrameGenKey;
    }
}
