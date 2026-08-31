package com.framegen.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrameCaptureManagerTest {

    private FrameCaptureManager captureManager;

    @BeforeEach
    void setUp() {
        captureManager = new FrameCaptureManager();
    }

    @Test
    void testInitialState() {
        assertThat(captureManager.getWidth()).isEqualTo(0);
        assertThat(captureManager.getHeight()).isEqualTo(0);
        assertThat(captureManager.isReady()).isFalse();
        assertThat(captureManager.hasPreviousFrame()).isFalse();
        assertThat(captureManager.getEntityMaskFBO()).isNull();
    }

    @Test
    void testResizeAndDimensions() {
        captureManager.ensureDimensions(1920, 1080);
        assertThat(captureManager.getWidth()).isEqualTo(1920);
        assertThat(captureManager.getHeight()).isEqualTo(1080);
        assertThat(captureManager.isReady()).isTrue();

        captureManager.advanceFrame();
        assertThat(captureManager.hasPreviousFrame()).isTrue();

        // Resizing dimensions must invalidate previous frame to prevent presenting from blank buffers
        captureManager.ensureDimensions(2560, 1440);
        assertThat(captureManager.getWidth()).isEqualTo(2560);
        assertThat(captureManager.getHeight()).isEqualTo(1440);
        assertThat(captureManager.hasPreviousFrame()).isFalse();
    }

    @Test
    void testBufferSwapping() {
        captureManager.ensureDimensions(800, 600);
        assertThat(captureManager.hasPreviousFrame()).isFalse();

        captureManager.advanceFrame();
        assertThat(captureManager.hasPreviousFrame()).isTrue();
        assertThat(captureManager.getFrameIndex()).isEqualTo(1);

        captureManager.advanceFrame();
        assertThat(captureManager.getFrameIndex()).isEqualTo(2);
    }

    @Test
    void testReset() {
        captureManager.ensureDimensions(800, 600);
        captureManager.advanceFrame();
        assertThat(captureManager.hasPreviousFrame()).isTrue();

        captureManager.reset();
        assertThat(captureManager.hasPreviousFrame()).isFalse();
        assertThat(captureManager.getFrameIndex()).isEqualTo(0);
    }
}
