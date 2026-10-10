package com.framegen.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FramePacerTest {

    private FramePacer framePacer;

    @BeforeEach
    void setUp() {
        framePacer = new FramePacer();
    }

    @Test
    void testInitialTelemetry() {
        assertThat(framePacer.getBaseFps()).isEqualTo(0.0);
        assertThat(framePacer.getEffectiveFps()).isEqualTo(0.0);
        assertThat(framePacer.getReprojectionTimeMs()).isEqualTo(0.0);
    }

    @Test
    void testFpsCalculationsWithFrameTime() {
        // Simulate a frame taking 16.666 ms (60 FPS base)
        framePacer.recordBaseFrame(16_666_666L);
        assertThat(framePacer.getBaseFps()).isGreaterThan(59.0).isLessThan(61.0);
        assertThat(framePacer.getEffectiveFps()).isGreaterThan(118.0).isLessThan(122.0);

        // Record reprojection time of 0.35 ms
        framePacer.recordReprojectionTime(350_000L);
        assertThat(framePacer.getReprojectionTimeMs()).isEqualTo(0.35);
    }

    @Test
    void testTelemetryStringFormatting() {
        framePacer.recordBaseFrame(16_666_666L);
        framePacer.recordReprojectionTime(350_000L);

        String telemetry = framePacer.getTelemetryString(true);
        assertThat(telemetry).contains("FrameGen 2x");
        assertThat(telemetry).contains("60");
        assertThat(telemetry).contains("120");
        assertThat(telemetry).contains("0.35 ms");
        assertThat(telemetry).contains("Omega");
        assertThat(telemetry).contains("ACTIVE");

        String disabledTelemetry = framePacer.getTelemetryString(false);
        assertThat(disabledTelemetry).contains("DISABLED");
    }

    @Test
    void testGatedTelemetryStringFormatting() {
        framePacer.recordBaseFrame(16_666_666L);
        framePacer.recordReprojectionTime(350_000L);
        framePacer.setLastFrameGated(true);

        String telemetry = framePacer.getTelemetryString(true);
        assertThat(telemetry).contains("GATED (SNAP)");
        assertThat(framePacer.isLastFrameGated()).isTrue();
    }

    @Test
    void testCpuWorkTimeRecording() {
        framePacer.recordBaseFrame(16_666_666L);
        framePacer.recordCpuWorkTime(11.4);
        assertThat(framePacer.getCpuWorkTimeMs()).isEqualTo(11.4);

        String telemetry = framePacer.getTelemetryString(true);
        assertThat(telemetry).contains("CPU work:");
        assertThat(telemetry).contains("11.4ms");
        assertThat(telemetry).contains("Reproject (CPU):");
        assertThat(telemetry).contains("1 (FIFO)");
    }

    @Test
    void testProbeCounterAndReset() {
        assertThat(framePacer.getProbeFrameCounter()).isEqualTo(0);
        assertThat(framePacer.getLastIntermediatePresentNanoTime()).isEqualTo(0);
        framePacer.recordBaseFrame(16_666_666L);
        assertThat(framePacer.getProbeFrameCounter()).isEqualTo(1);
        framePacer.reset();
        assertThat(framePacer.getLastIntermediatePresentNanoTime()).isEqualTo(0);
    }
}
