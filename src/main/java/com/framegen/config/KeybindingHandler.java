package com.framegen.config;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

public class KeybindingHandler {
    private static KeyMapping toggleFrameGenKey;
    private static KeyMapping cycleFlowPresetKey;

    public static void register() {
        toggleFrameGenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.framegen.toggle",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_F8,
                KeyMapping.Category.MISC
        ));

        cycleFlowPresetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.framegen.flow_preset",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_F9,
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

            while (cycleFlowPresetKey.consumeClick()) {
                FrameGenConfig config = FrameGenConfig.getInstance();
                int mode = config.cycleOpticalFlowQualityMode();
                config.save();

                if (client.player != null) {
                    String modeText = switch (mode) {
                        case 1 -> "§aPERFORMANCE (4-Tap Fast MX330 Mode)§r";
                        case 2 -> "§bQUALITY (12-Tap Ultra Flow Mode)§r";
                        default -> "§eOFF (Camera + Entity MV Only)§r";
                    };
                    Component message = Component.literal("§6[FrameGen]§r Optical Flow Preset: " + modeText);
                    client.player.sendOverlayMessage(message);
                }
            }
        });
    }

    public static KeyMapping getToggleKey() {
        return toggleFrameGenKey;
    }

    public static KeyMapping getCycleFlowPresetKey() {
        return cycleFlowPresetKey;
    }
}
