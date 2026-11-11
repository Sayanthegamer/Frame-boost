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
        assertThat(config.isOpticalFlowEnabled()).isTrue();
        assertThat(config.getOpticalFlowQualityMode()).isEqualTo(1);
        assertThat(config.getOpticalFlowPresetLabel()).isEqualTo("PERF");
        assertThat(config.getOpticalFlowSearchRadius()).isEqualTo(3.0f);
        assertThat(config.isEntityMotionVectorsEnabled()).isTrue();
        assertThat(config.getEntityVelocityMaxUv()).isEqualTo(0.125f);
        assertThat(config.isViewmodelMotionInterpolationEnabled()).isTrue();
        assertThat(config.isDebugLadderTintEnabled()).isFalse();
        assertThat(config.isDebugLogGlDrift()).isFalse();
        assertThat(config.isCadencePacingEnabled()).isFalse();
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
    void testCycleOpticalFlowQualityMode() {
        FrameGenConfig config = new FrameGenConfig();
        // Starts at 1 (PERFORMANCE)
        assertThat(config.getOpticalFlowQualityMode()).isEqualTo(1);
        assertThat(config.getOpticalFlowPresetLabel()).isEqualTo("PERF");

        // 1 -> 2 (QUALITY)
        assertThat(config.cycleOpticalFlowQualityMode()).isEqualTo(2);
        assertThat(config.isOpticalFlowEnabled()).isTrue();
        assertThat(config.getOpticalFlowPresetLabel()).isEqualTo("QUAL");

        // 2 -> 0 (OFF)
        assertThat(config.cycleOpticalFlowQualityMode()).isEqualTo(0);
        assertThat(config.isOpticalFlowEnabled()).isFalse();
        assertThat(config.getOpticalFlowPresetLabel()).isEqualTo("OFF");

        // 0 -> 1 (PERFORMANCE)
        assertThat(config.cycleOpticalFlowQualityMode()).isEqualTo(1);
        assertThat(config.isOpticalFlowEnabled()).isTrue();
        assertThat(config.getOpticalFlowPresetLabel()).isEqualTo("PERF");
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

        config.setOpticalFlowQualityMode(-5);
        assertThat(config.getOpticalFlowQualityMode()).isEqualTo(0);

        config.setOpticalFlowQualityMode(99);
        assertThat(config.getOpticalFlowQualityMode()).isEqualTo(2);

        config.setOpticalFlowSearchRadius(-3.0f);
        assertThat(config.getOpticalFlowSearchRadius()).isEqualTo(0.0f);

        config.setOpticalFlowSearchRadius(25.0f);
        assertThat(config.getOpticalFlowSearchRadius()).isEqualTo(8.0f);

        config.setEntityVelocityMaxUv(0.0001f);
        assertThat(config.getEntityVelocityMaxUv()).isEqualTo(0.01f);

        config.setEntityVelocityMaxUv(2.0f);
        assertThat(config.getEntityVelocityMaxUv()).isEqualTo(0.5f);
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
        original.setOpticalFlowEnabled(true);
        original.setOpticalFlowQualityMode(2);
        original.setOpticalFlowSearchRadius(4.5f);
        original.setEntityMotionVectorsEnabled(false);
        original.setEntityVelocityMaxUv(0.25f);
        original.setViewmodelMotionInterpolationEnabled(false);

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
        assertThat(loaded.isOpticalFlowEnabled()).isTrue();
        assertThat(loaded.getOpticalFlowQualityMode()).isEqualTo(2);
        assertThat(loaded.getOpticalFlowSearchRadius()).isEqualTo(4.5f);
        assertThat(loaded.isEntityMotionVectorsEnabled()).isFalse();
        assertThat(loaded.getEntityVelocityMaxUv()).isEqualTo(0.25f);
        assertThat(loaded.isViewmodelMotionInterpolationEnabled()).isFalse();
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
