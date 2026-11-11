package com.framegen.core;

import com.framegen.capture.FrameCaptureManager;
import com.framegen.config.FrameGenConfig;
import com.framegen.shader.ReprojectionShaderPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
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
    private long renderStartNanoTime = 0;
    private volatile double cpuWorkTimeMs = 0.0;
    private volatile long lastIntermediatePresentNanoTime = 0;
    private long probeFrameCounter = 0;

    private volatile boolean intermediateSynthesisSuccess = false;
    private volatile boolean lastSynthesisSuccess = false;

    public FramePacer() {
    }

    public static synchronized FramePacer getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FramePacer();
        }
        return INSTANCE;
    }

    public boolean isIntermediateSynthesisSuccess() {
        return intermediateSynthesisSuccess;
    }

    public boolean isLastSynthesisSuccess() {
        return lastSynthesisSuccess;
    }

    public synchronized void recordRenderStart() {
        this.renderStartNanoTime = System.nanoTime();
    }

    public synchronized void recordRenderEnd() {
        if (this.renderStartNanoTime > 0) {
            long duration = System.nanoTime() - this.renderStartNanoTime;
            this.cpuWorkTimeMs = duration / 1_000_000.0;
        }
    }

    public synchronized void recordCpuWorkTime(double cpuWorkTimeMs) {
        this.cpuWorkTimeMs = cpuWorkTimeMs;
    }

    public double getCpuWorkTimeMs() {
        return cpuWorkTimeMs;
    }

    public synchronized void reset() {
        this.intermediateSynthesisSuccess = false;
        this.lastFrameNanoTime = 0;
        this.lastFrameDurationNanos = 0;
        this.baseFps = 0.0;
        this.effectiveFps = 0.0;
        this.reprojectionTimeMs = 0.0;
        this.lastFrameGated = false;
        this.renderStartNanoTime = 0;
        this.cpuWorkTimeMs = 0.0;
        this.lastIntermediatePresentNanoTime = 0;
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

            if (++this.probeFrameCounter % 120 == 0) {
                String timeStr = reprojectionTimeMs < 0.01
                        ? String.format(Locale.ROOT, "%.3f ms", reprojectionTimeMs)
                        : String.format(Locale.ROOT, "%.2f ms", reprojectionTimeMs);
                float angularVel = CameraMatrixTracker.getInstance().getAngularVelocityDegPerSec();
                int movingEntities = EntityVelocityTracker.getInstance().getActiveMovingEntityCount();
                String flowPreset = FrameGenConfig.getInstance().getOpticalFlowPresetLabel();
                LOGGER.info(String.format(Locale.ROOT,
                        "[FrameGen Telemetry Probe] Base: %.1f FPS | Eff: %.1f Hz | CPU work: %.2f ms | Reproject: %s | Flow: %s | Omega: %.1f deg/s | EntMV: %d | Gated: %b | SynthOk: %b",
                        baseFps, effectiveFps, cpuWorkTimeMs, timeStr, flowPreset, angularVel, movingEntities, lastFrameGated, lastSynthesisSuccess));
            }
        }
    }

    public synchronized void recordReprojectionTime(long reprojectionDurationNanos) {
        this.reprojectionTimeMs = (double) reprojectionDurationNanos / 1_000_000.0;
    }

    public void sanitizeRenderTargetAlpha(RenderTarget target) {
        if (target != null && target.getColorTexture() != null && target.width > 0 && target.height > 0) {
            shaderPass.sanitizeAlpha(target.getColorTexture(), target.width, target.height);
        }
    }

    public void sanitizeWindowBackbufferAlpha() {
        shaderPass.sanitizeDefaultFramebufferAlpha();
    }

    public void onRenderTick(
            RenderTarget mainTarget,
            CameraMatrixTracker matrixTracker,
            FrameCaptureManager captureManager
    ) {
        this.lastSynthesisSuccess = this.intermediateSynthesisSuccess;
        this.intermediateSynthesisSuccess = false;
        this.lastIntermediatePresentNanoTime = 0;
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
                config.getMaxTranslationVelocityThreshold(),
                config.getMaxAbsoluteAngleThreshold(),
                config.getMaxFrameDurationThresholdMs()
        );
        this.lastFrameGated = isGated;

        if (isGated) {
            this.intermediateSynthesisSuccess = false;
            return;
        }

        long startReprojection = System.nanoTime();

        try {
            TextureTarget currentFBO = captureManager.getCurrentFrameFBO();
            TextureTarget previousFBO = captureManager.getPreviousFrameFBO();
            TextureTarget intermediateFBO = captureManager.getIntermediateFrameFBO();
            TextureTarget entityMaskFBO = captureManager.getEntityMaskFBO();

            if (currentFBO != null && previousFBO != null && intermediateFBO != null) {
                if (config.isDebugSkipRender()) {
                    LOGGER.info("[DebugSkipRender] Skipping shaderPass.render to test if GL state pollution causes black screen.");
                    return;
                }

                // Phase 2: Compute and rasterize per-entity 3D motion vector quads into entityMaskFBO
                if (entityMaskFBO != null && entityMaskFBO.getColorTexture() != null) {
                    List<EntityVelocityTracker.EntityVelocityQuad> velocityQuads = config.isEntityMotionVectorsEnabled()
                            ? EntityVelocityTracker.getInstance().computeVelocityQuads(matrixTracker, config.getEntityVelocityMaxUv())
                            : Collections.emptyList();
                    shaderPass.renderEntityVelocityMask(
                            entityMaskFBO.getColorTexture(),
                            mainTarget.width,
                            mainTarget.height,
                            velocityQuads
                    );
                }

                boolean success = shaderPass.render(
                        currentFBO.getColorTexture(),
                        previousFBO.getColorTexture(),
                        currentFBO.getDepthTexture(),
                        previousFBO.getDepthTexture(),
                        entityMaskFBO != null ? entityMaskFBO.getColorTexture() : null,
                        intermediateFBO.getColorTexture(),
                        matrixTracker.getMidToPrevMatrix(),
                        matrixTracker.getMidToCurrMatrix(),
                        matrixTracker.getMidToPrevSkyMatrix(),
                        matrixTracker.getMidToCurrSkyMatrix(),
                        mainTarget.width,
                        mainTarget.height,
                        config.getInterpolationFactor(),
                        config.getDepthDilationRadius(),
                        config.isDebugLadderTintEnabled(),
                        config.isOpticalFlowEnabled(),
                        config.getOpticalFlowQualityMode(),
                        config.getOpticalFlowSearchRadius(),
                        config.getEntityVelocityMaxUv()
                );
                this.intermediateSynthesisSuccess = success;
            }
        } catch (Throwable t) {
            LOGGER.error("Error during FramePacer intermediate frame synthesis pass", t);
        } finally {
            long endReprojection = System.nanoTime();
            recordReprojectionTime(endReprojection - startReprojection);
        }
    }

    public boolean presentIntermediateFrame(GpuSurface windowSurface, RenderTarget mainTarget) {
        this.lastIntermediatePresentNanoTime = 0;
        FrameGenConfig config = FrameGenConfig.getInstance();
        FrameCaptureManager captureManager = FrameCaptureManager.getInstance();

        if (!config.isEnabled() || !captureManager.hasPreviousFrame() || !this.intermediateSynthesisSuccess || windowSurface == null || !windowSurface.isAcquired()) {
            return false;
        }

        TextureTarget intermediateFBO = captureManager.getIntermediateFrameFBO();
        TextureTarget currentFBO = captureManager.getCurrentFrameFBO();
        TextureTarget previousFBO = captureManager.getPreviousFrameFBO();
        TextureTarget currPostHandFBO = captureManager.getCurrentPostHandFBO();
        TextureTarget prevPostHandFBO = captureManager.getPreviousPostHandFBO();
        TextureTarget hudFBO = captureManager.getHudCompositeFBO();

        if (intermediateFBO == null || intermediateFBO.getColorTextureView() == null) {
            return false;
        }

        try {
            TextureTarget targetToPresent = intermediateFBO;

            // Phase 3 & 4: Composite T=0.5 3D First-Person Hand and static 2D GUI over intermediate world frame
            if (hudFBO != null && mainTarget != null && currentFBO != null &&
                    mainTarget.getColorTexture() != null && currentFBO.getColorTexture() != null) {
                boolean useHandInterp = config.isViewmodelMotionInterpolationEnabled()
                        && captureManager.isCapturedPostHandThisFrame()
                        && captureManager.hasPreviousPostHandFrame()
                        && currPostHandFBO != null && prevPostHandFBO != null && previousFBO != null;

                boolean hudOk = shaderPass.compositeHud(
                        intermediateFBO.getColorTexture(),
                        mainTarget.getColorTexture(),
                        currentFBO.getColorTexture(),
                        useHandInterp ? currPostHandFBO.getColorTexture() : null,
                        useHandInterp ? prevPostHandFBO.getColorTexture() : null,
                        useHandInterp ? previousFBO.getColorTexture() : null,
                        hudFBO.getColorTexture(),
                        mainTarget.width,
                        mainTarget.height,
                        useHandInterp,
                        config.getInterpolationFactor()
                );
                if (hudOk) {
                    targetToPresent = hudFBO;
                }
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            windowSurface.blitFromTexture(encoder, targetToPresent.getColorTextureView());
            encoder.submit();
            shaderPass.sanitizeDefaultFramebufferAlpha();
            windowSurface.present();
            this.lastIntermediatePresentNanoTime = System.nanoTime();
            windowSurface.acquireNextTexture();

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
        float angularVel = CameraMatrixTracker.getInstance().getAngularVelocityDegPerSec();
        int movingEntities = EntityVelocityTracker.getInstance().getActiveMovingEntityCount();
        String flowPreset = FrameGenConfig.getInstance().getOpticalFlowPresetLabel();
        return String.format(
                Locale.ROOT,
                "§6[FrameGen 2x]§r Base: §a%.0f§r / §b%.0f Hz§r (CPU work: §e%.1fms§r) | Reproject (CPU): §e%s§r | Flow: §b%s§r | Swap: §b1 (FIFO)§r | Omega: §b%.0f°/s§r | EntMV: §d%d§r | Status: %s",
                baseFps,
                effectiveFps,
                cpuWorkTimeMs,
                timeStr,
                flowPreset,
                angularVel,
                movingEntities,
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

    public long getLastIntermediatePresentNanoTime() {
        return lastIntermediatePresentNanoTime;
    }

    public long getProbeFrameCounter() {
        return probeFrameCounter;
    }

    public synchronized long computeTargetHalfIntervalNanos() {
        if (this.baseFps > 1.0) {
            // Half-step interval: (1 second / baseFps) * 0.5 in nanoseconds
            return (long) (500_000_000.0 / this.baseFps);
        }
        // Fallback default for 60Hz display (16.666 ms half-step)
        return 16_666_666L;
    }

    public synchronized long computeRemainingWaitNanos(long elapsedNanos, long targetHalfIntervalNanos) {
        if (this.lastFrameGated) {
            return 0L;
        }
        long remaining = targetHalfIntervalNanos - elapsedNanos;
        return Math.max(0L, remaining);
    }

    public void performHybridWait(long waitNanos) {
        if (waitNanos <= 0) {
            return;
        }
        long targetEnd = System.nanoTime() + waitNanos;
        // Reserve a 3.5 ms spin margin so Windows timer resolution quantums (~4-15 ms on LockSupport.parkNanos)
        // never overshoot short waits (< 3.5 ms) during sub-frame cadence pacing or unit tests.
        long coarseNanos = waitNanos - 3_500_000L;
        if (coarseNanos > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(coarseNanos);
        }
        // Fine-grained busy spin for terminal precision
        while (System.nanoTime() < targetEnd) {
            Thread.onSpinWait();
        }
    }

    public void paceIntermediatePresentation(long intermPresentNanoTime) {
        if (!FrameGenConfig.getInstance().isCadencePacingEnabled() || intermPresentNanoTime <= 0 || this.lastFrameGated) {
            return;
        }
        long elapsedSincePresent = System.nanoTime() - intermPresentNanoTime;
        long targetHalfInterval = computeTargetHalfIntervalNanos();
        long remainingWait = computeRemainingWaitNanos(elapsedSincePresent, targetHalfInterval);
        if (remainingWait > 0) {
            performHybridWait(remainingWait);
        }
    }
}
