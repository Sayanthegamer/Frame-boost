package com.framegen;

import com.framegen.config.FrameGenConfig;
import com.framegen.config.KeybindingHandler;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameGenClient implements ClientModInitializer {
    public static final String MOD_ID = "framegen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Universal Frame Generation (2x Reprojection Engine) initializing...");
        FrameGenConfig.getInstance();
        KeybindingHandler.register();

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("World disconnect detected; resetting frame generation buffers and tracking state.");
            com.framegen.capture.FrameCaptureManager.getInstance().reset();
            com.framegen.core.CameraMatrixTracker.getInstance().reset();
            com.framegen.core.FramePacer.getInstance().reset();
        });

        LOGGER.info("Universal Frame Generation initialized successfully.");
    }
}
