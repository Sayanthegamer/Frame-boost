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
        LOGGER.info("Universal Frame Generation initialized successfully.");
    }
}
