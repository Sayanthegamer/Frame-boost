package com.framegen.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrameGenConfigTest {

    @Test
    void testDefaultValues() {
        FrameGenConfig config = new FrameGenConfig();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getInterpolationFactor()).isEqualTo(0.5f);
        assertThat(config.isDebugOverlayEnabled()).isTrue();
        assertThat(config.getDepthDilationRadius()).isEqualTo(1.0f);
        assertThat(config.isVelocityGatingEnabled()).isTrue();
        assertThat(config.getMaxAngularVelocityThreshold()).isEqualTo(720.0f);
        assertThat(config.getMaxTranslationVelocityThreshold()).isEqualTo(50.0f);
        assertThat(config.getMaxAbsoluteAngleThreshold()).isEqualTo(15.0f);
        assertThat(config.getMaxFrameDurationThresholdMs()).isEqualTo(50.0f);
        assertThat(config.isDebugLadderTintEnabled()).isFalse();
        assertThat(config.isDebugLogGlDrift()).isFalse();
    }

    @Test
    void testToggleEnabled() {
        FrameGenConfig config = new FrameGenConfig();
        assertThat(config.isEnabled()).isTrue();
        boolean newState = config.toggleEnabled();
        assertThat(newState).isFalse();
        assertThat(config.isEnabled()).isFalse();
        
        newState = config.toggleEnabled();
        assertThat(newState).isTrue();
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void testValidationAndClamping() {
        FrameGenConfig config = new FrameGenConfig();
        config.setInterpolationFactor(-0.5f);
        assertThat(config.getInterpolationFactor()).isEqualTo(0.0f);

        config.setInterpolationFactor(1.5f);
        assertThat(config.getInterpolationFactor()).isEqualTo(1.0f);

        config.setDepthDilationRadius(-2.0f);
        assertThat(config.getDepthDilationRadius()).isEqualTo(0.0f);

        config.setDepthDilationRadius(10.0f);
        assertThat(config.getDepthDilationRadius()).isEqualTo(5.0f);

        config.setMaxAngularVelocityThreshold(0.0f);
        assertThat(config.getMaxAngularVelocityThreshold()).isEqualTo(10.0f);

        config.setMaxAngularVelocityThreshold(10000.0f);
        assertThat(config.getMaxAngularVelocityThreshold()).isEqualTo(7200.0f);

        config.setMaxTranslationVelocityThreshold(0.05f);
        assertThat(config.getMaxTranslationVelocityThreshold()).isEqualTo(1.0f);

        config.setMaxTranslationVelocityThreshold(1000.0f);
        assertThat(config.getMaxTranslationVelocityThreshold()).isEqualTo(500.0f);

        config.setMaxAbsoluteAngleThreshold(0.0f);
        assertThat(config.getMaxAbsoluteAngleThreshold()).isEqualTo(1.0f);

        config.setMaxAbsoluteAngleThreshold(180.0f);
        assertThat(config.getMaxAbsoluteAngleThreshold()).isEqualTo(90.0f);

        config.setMaxFrameDurationThresholdMs(5.0f);
        assertThat(config.getMaxFrameDurationThresholdMs()).isEqualTo(10.0f);

        config.setMaxFrameDurationThresholdMs(1000.0f);
        assertThat(config.getMaxFrameDurationThresholdMs()).isEqualTo(500.0f);
    }

    @Test
    void testJsonSerializationAndDeserialization(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("framegen.json");
        FrameGenConfig original = new FrameGenConfig();
        original.setEnabled(false);
        original.setInterpolationFactor(0.5f);
        original.setDebugOverlayEnabled(false);
        original.setDepthDilationRadius(2.0f);
        original.setVelocityGatingEnabled(false);
        original.setMaxAngularVelocityThreshold(45.0f);
        original.setMaxTranslationVelocityThreshold(15.0f);

        FrameGenConfig.save(original, configFile);
        assertThat(Files.exists(configFile)).isTrue();

        FrameGenConfig loaded = FrameGenConfig.load(configFile);
        assertThat(loaded.isEnabled()).isFalse();
        assertThat(loaded.getInterpolationFactor()).isEqualTo(0.5f);
        assertThat(loaded.isDebugOverlayEnabled()).isFalse();
        assertThat(loaded.getDepthDilationRadius()).isEqualTo(2.0f);
        assertThat(loaded.isVelocityGatingEnabled()).isFalse();
        assertThat(loaded.getMaxAngularVelocityThreshold()).isEqualTo(45.0f);
        assertThat(loaded.getMaxTranslationVelocityThreshold()).isEqualTo(15.0f);
    }

    @Test
    void testLoadNonExistentFileReturnsDefaultsAndCreatesFile(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve("framegen_nonexistent.json");
        FrameGenConfig loaded = FrameGenConfig.load(configFile);
        assertThat(loaded).isNotNull();
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(Files.exists(configFile)).isTrue();
    }
}
