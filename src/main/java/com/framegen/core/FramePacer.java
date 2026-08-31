package com.framegen.core;

import com.framegen.capture.FrameCaptureManager;
import com.framegen.config.FrameGenConfig;
import com.framegen.shader.ReprojectionShaderPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class FramePacer {
    private static final Logger LOGGER = LoggerFactory.getLogger("FramePacer");
    private static FramePacer INSTANCE;

    private final ReprojectionShaderPass shaderPass = new ReprojectionShaderPass();

    private long lastFrameNanoTime = 0;
    private long lastFrameDurationNanos = 0;
    private double baseFps = 0.0;
    private double effectiveFps = 0.0;
    private double reprojectionTimeMs = 0.0;
    private boolean lastFrameGated = false;

    public FramePacer() {
    }

    public static synchronized FramePacer getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FramePacer();
        }
        return INSTANCE;
    }

    public synchronized void recordBaseFrame(long frameDurationNanos) {
        if (frameDurationNanos > 0) {
            this.lastFrameDurationNanos = frameDurationNanos;
            double fps = 1_000_000_000.0 / (double) frameDurationNanos;
            // Exponential moving average for smooth display
            if (this.baseFps == 0.0) {
                this.baseFps = fps;
            } else {
                this.baseFps = (this.baseFps * 0.9) + (fps * 0.1);
            }
            this.effectiveFps = this.baseFps * 2.0;
        }
    }

    public synchronized void recordReprojectionTime(long reprojectionDurationNanos) {
        this.reprojectionTimeMs = (double) reprojectionDurationNanos / 1_000_000.0;
    }

    public void onRenderTick(
            RenderTarget mainTarget,
            CameraMatrixTracker matrixTracker,
            FrameCaptureManager captureManager
    ) {
        long currentNanoTime = System.nanoTime();
        if (lastFrameNanoTime != 0) {
            recordBaseFrame(currentNanoTime - lastFrameNanoTime);
        }
        lastFrameNanoTime = currentNanoTime;

        FrameGenConfig config = FrameGenConfig.getInstance();
        if (!config.isEnabled() || !captureManager.hasPreviousFrame() || mainTarget == null) {
            return;
        }

        boolean isGated = config.isVelocityGatingEnabled() && matrixTracker.isVelocityGated(
                config.getMaxAngularVelocityThreshold(),
                config.getMaxTranslationVelocityThreshold()
        );
        this.lastFrameGated = isGated;

        long startReprojection = System.nanoTime();

        try {
            TextureTarget currentFBO = captureManager.getCurrentFrameFBO();
            TextureTarget previousFBO = captureManager.getPreviousFrameFBO();
            TextureTarget intermediateFBO = captureManager.getIntermediateFrameFBO();
            TextureTarget entityMaskFBO = captureManager.getEntityMaskFBO();

            if (currentFBO != null && previousFBO != null && intermediateFBO != null) {
                float effectiveBlend = isGated ? 0.0f : config.getInterpolationFactor();
                shaderPass.render(
                        currentFBO.getColorTexture(),
                        previousFBO.getColorTexture(),
                        currentFBO.getDepthTexture(),
                        previousFBO.getDepthTexture(),
                        entityMaskFBO != null ? entityMaskFBO.getColorTexture() : null,
                        intermediateFBO.getColorTexture(),
                        matrixTracker.getReprojectionMatrix(),
                        mainTarget.width,
                        mainTarget.height,
                        effectiveBlend,
                        config.getDepthDilationRadius()
                );
            }
        } catch (Throwable t) {
            LOGGER.error("Error during FramePacer intermediate frame synthesis pass", t);
        } finally {
            long endReprojection = System.nanoTime();
            recordReprojectionTime(endReprojection - startReprojection);
        }
    }

    public boolean presentIntermediateFrame(com.mojang.blaze3d.systems.GpuSurface windowSurface, RenderTarget mainTarget) {
        FrameGenConfig config = FrameGenConfig.getInstance();
        FrameCaptureManager captureManager = FrameCaptureManager.getInstance();

        if (!config.isEnabled() || !captureManager.hasPreviousFrame() || windowSurface == null || !windowSurface.isAcquired()) {
            return false;
        }

        TextureTarget intermediateFBO = captureManager.getIntermediateFrameFBO();
        TextureTarget currentFBO = captureManager.getCurrentFrameFBO();
        TextureTarget hudFBO = captureManager.getHudCompositeFBO();

        if (intermediateFBO == null || intermediateFBO.getColorTextureView() == null) {
            return false;
        }

        try {
            TextureTarget targetToPresent = intermediateFBO;

            // Composite HUD onto intermediate frame to eliminate UI flickering
            if (hudFBO != null && mainTarget != null && currentFBO != null &&
                    mainTarget.getColorTexture() != null && currentFBO.getColorTexture() != null) {
                shaderPass.compositeHud(
                        intermediateFBO.getColorTexture(),
                        mainTarget.getColorTexture(),
                        currentFBO.getColorTexture(),
                        hudFBO.getColorTexture(),
                        mainTarget.width,
                        mainTarget.height
                );
                targetToPresent = hudFBO;
            }

            com.mojang.blaze3d.systems.CommandEncoder encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
            windowSurface.blitFromTexture(encoder, targetToPresent.getColorTextureView());
            encoder.submit();
            windowSurface.present();
            windowSurface.acquireNextTexture();

            // Precision frame pacing: pace half of the base frame interval to eliminate scanline tearing
            long halfIntervalNanos = this.lastFrameDurationNanos / 2;
            if (halfIntervalNanos > 1_000_000 && halfIntervalNanos < 40_000_000) {
                long deadline = System.nanoTime() + halfIntervalNanos - 300_000;
                long sleepNanos = deadline - System.nanoTime();
                if (sleepNanos > 2_000_000) {
                    java.util.concurrent.locks.LockSupport.parkNanos(sleepNanos - 1_000_000);
                }
                while (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            }

            return true;
        } catch (Throwable t) {
            LOGGER.error("Error presenting intermediate frame to display surface", t);
            return false;
        }
    }

    public String getTelemetryString(boolean isEnabled) {
        if (!isEnabled) {
            return "§6[FrameGen 2x]§r Status: §cDISABLED§r";
        }
        String timeStr = reprojectionTimeMs < 0.01
                ? String.format(Locale.ROOT, "%.3f ms", reprojectionTimeMs)
                : String.format(Locale.ROOT, "%.2f ms", reprojectionTimeMs);
        String statusStr = lastFrameGated ? "§eGATED (SNAP)§r" : "§aACTIVE§r";
        float angularDelta = CameraMatrixTracker.getInstance().getAngularDeltaDegrees();
        return String.format(
                Locale.ROOT,
                "§6[FrameGen 2x]§r Base: §a%.0f§r FPS | 2x Target: §b%.0f§r FPS | Reproject: §e%s§r | RotDelta: §b%.1f°§r | Status: %s",
                baseFps,
                effectiveFps,
                timeStr,
                angularDelta,
                statusStr
        );
    }

    public boolean isLastFrameGated() {
        return lastFrameGated;
    }

    public void setLastFrameGated(boolean lastFrameGated) {
        this.lastFrameGated = lastFrameGated;
    }

    public double getBaseFps() {
        return baseFps;
    }

    public double getEffectiveFps() {
        return effectiveFps;
    }

    public double getReprojectionTimeMs() {
        return reprojectionTimeMs;
    }

    public ReprojectionShaderPass getShaderPass() {
        return shaderPass;
    }
}
