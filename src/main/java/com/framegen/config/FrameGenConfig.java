package com.framegen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FrameGenConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameGenConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DEFAULT_PATH = Paths.get("config", "framegen.json");

    private static FrameGenConfig INSTANCE;

    private boolean enabled = true;
    private float interpolationFactor = 0.5f;
    private boolean debugOverlayEnabled = true;
    private float depthDilationRadius = 1.0f;
    private boolean velocityGatingEnabled = true;
    private float maxAngularVelocityThreshold = 30.0f;
    private float maxTranslationVelocityThreshold = 10.0f;

    public FrameGenConfig() {
    }

    public static synchronized FrameGenConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load(DEFAULT_PATH);
        }
        return INSTANCE;
    }

    public static FrameGenConfig load(Path path) {
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                FrameGenConfig config = GSON.fromJson(reader, FrameGenConfig.class);
                if (config != null) {
                    config.validate();
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load framegen config from {}, falling back to defaults", path, e);
            }
        }
        FrameGenConfig defaultConfig = new FrameGenConfig();
        save(defaultConfig, path);
        return defaultConfig;
    }

    public static void save(FrameGenConfig config, Path path) {
        if (config == null) return;
        config.validate();
        try {
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save framegen config to {}", path, e);
        }
    }

    public void save() {
        save(this, DEFAULT_PATH);
    }

    public void validate() {
        if (this.interpolationFactor < 0.0f) {
            this.interpolationFactor = 0.0f;
        } else if (this.interpolationFactor > 1.0f) {
            this.interpolationFactor = 1.0f;
        }

        if (this.depthDilationRadius < 0.0f) {
            this.depthDilationRadius = 0.0f;
        } else if (this.depthDilationRadius > 5.0f) {
            this.depthDilationRadius = 5.0f;
        }

        if (this.maxAngularVelocityThreshold < 1.0f) {
            this.maxAngularVelocityThreshold = 1.0f;
        } else if (this.maxAngularVelocityThreshold > 180.0f) {
            this.maxAngularVelocityThreshold = 180.0f;
        }

        if (this.maxTranslationVelocityThreshold < 0.1f) {
            this.maxTranslationVelocityThreshold = 0.1f;
        } else if (this.maxTranslationVelocityThreshold > 100.0f) {
            this.maxTranslationVelocityThreshold = 100.0f;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean toggleEnabled() {
        this.enabled = !this.enabled;
        return this.enabled;
    }

    public float getInterpolationFactor() {
        return interpolationFactor;
    }

    public void setInterpolationFactor(float interpolationFactor) {
        this.interpolationFactor = interpolationFactor;
        validate();
    }

    public boolean isDebugOverlayEnabled() {
        return debugOverlayEnabled;
    }

    public void setDebugOverlayEnabled(boolean debugOverlayEnabled) {
        this.debugOverlayEnabled = debugOverlayEnabled;
    }

    public float getDepthDilationRadius() {
        return depthDilationRadius;
    }

    public void setDepthDilationRadius(float depthDilationRadius) {
        this.depthDilationRadius = depthDilationRadius;
        validate();
    }

    public boolean isVelocityGatingEnabled() {
        return velocityGatingEnabled;
    }

    public void setVelocityGatingEnabled(boolean velocityGatingEnabled) {
        this.velocityGatingEnabled = velocityGatingEnabled;
    }

    public float getMaxAngularVelocityThreshold() {
        return maxAngularVelocityThreshold;
    }

    public void setMaxAngularVelocityThreshold(float maxAngularVelocityThreshold) {
        this.maxAngularVelocityThreshold = maxAngularVelocityThreshold;
        validate();
    }

    public float getMaxTranslationVelocityThreshold() {
        return maxTranslationVelocityThreshold;
    }

    public void setMaxTranslationVelocityThreshold(float maxTranslationVelocityThreshold) {
        this.maxTranslationVelocityThreshold = maxTranslationVelocityThreshold;
        validate();
    }
}
