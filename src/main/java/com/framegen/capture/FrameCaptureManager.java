package com.framegen.capture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameCaptureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameCaptureManager");

    private static FrameCaptureManager INSTANCE;

    private TextureTarget currentFrameFBO;
    private TextureTarget previousFrameFBO;
    private TextureTarget intermediateFrameFBO;
    private TextureTarget hudCompositeFBO;
    private TextureTarget entityMaskFBO;
    private TextureTarget worldOnlyFBO;

    private int width = 0;
    private int height = 0;
    private long frameIndex = 0;
    private boolean hasPreviousFrame = false;
    private boolean ready = false;

    public FrameCaptureManager() {
    }

    public static synchronized FrameCaptureManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FrameCaptureManager();
        }
        return INSTANCE;
    }

    public synchronized void ensureDimensions(int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            return;
        }

        if (this.width != targetWidth || this.height != targetHeight || currentFrameFBO == null) {
            this.width = targetWidth;
            this.height = targetHeight;
            this.ready = true;
            this.hasPreviousFrame = false;

            try {
                if (RenderSystem.isOnRenderThread() && RenderSystem.tryGetDevice() != null) {
                    recreateBuffers();
                }
            } catch (Throwable t) {
                LOGGER.warn("Deferred buffer creation (running outside active GL render thread): {}", t.getMessage());
            }
        }
    }

    private GpuTexture lastDepthTexture;

    private void recreateBuffers() {
        destroyBuffers();
        this.hasPreviousFrame = false;

        try {
            currentFrameFBO = new TextureTarget("framegen_current", width, height, true, GpuFormat.RGBA8_UNORM);
            previousFrameFBO = new TextureTarget("framegen_previous", width, height, true, GpuFormat.RGBA8_UNORM);
            intermediateFrameFBO = new TextureTarget("framegen_intermediate", width, height, false, GpuFormat.RGBA8_UNORM);
            hudCompositeFBO = new TextureTarget("framegen_hud_composite", width, height, false, GpuFormat.RGBA8_UNORM);
            entityMaskFBO = new TextureTarget("framegen_entity_mask", width, height, false, GpuFormat.RGBA8_UNORM);
            worldOnlyFBO = new TextureTarget("framegen_world_only", width, height, true, GpuFormat.RGBA8_UNORM);
        } catch (Throwable t) {
            LOGGER.error("Failed to allocate FrameCaptureManager RenderTargets", t);
        }
    }

    public synchronized void captureWorldOnly(RenderTarget sourceTarget) {
        if (sourceTarget == null || sourceTarget.width <= 0 || sourceTarget.height <= 0) {
            return;
        }

        ensureDimensions(sourceTarget.width, sourceTarget.height);

        try {
            if (worldOnlyFBO != null && sourceTarget.getColorTexture() != null && worldOnlyFBO.getColorTexture() != null) {
                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                encoder.copyTextureToTexture(
                        sourceTarget.getColorTexture(),
                        worldOnlyFBO.getColorTexture(),
                        0,
                        0, 0,
                        0, 0,
                        width, height
                );

                if (sourceTarget.getDepthTexture() != null && worldOnlyFBO.getDepthTexture() != null) {
                    encoder.copyTextureToTexture(
                            sourceTarget.getDepthTexture(),
                            worldOnlyFBO.getDepthTexture(),
                            0,
                            0, 0,
                            0, 0,
                            width, height
                    );
                }

                encoder.submit();
            }
        } catch (Throwable t) {
            LOGGER.error("Error capturing unoccluded world background textures", t);
        }
    }

    public synchronized void captureCurrentFrame(RenderTarget sourceTarget) {
        if (sourceTarget == null || sourceTarget.width <= 0 || sourceTarget.height <= 0) {
            return;
        }

        ensureDimensions(sourceTarget.width, sourceTarget.height);

        try {
            if (currentFrameFBO != null && sourceTarget.getColorTexture() != null && currentFrameFBO.getColorTexture() != null) {
                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                encoder.copyTextureToTexture(
                        sourceTarget.getColorTexture(),
                        currentFrameFBO.getColorTexture(),
                        0,
                        0, 0,
                        0, 0,
                        width, height
                );

                if (sourceTarget.getDepthTexture() != null && currentFrameFBO.getDepthTexture() != null) {
                    encoder.copyTextureToTexture(
                            sourceTarget.getDepthTexture(),
                            currentFrameFBO.getDepthTexture(),
                            0,
                            0, 0,
                            0, 0,
                            width, height
                    );
                }

                encoder.submit();
                this.lastDepthTexture = currentFrameFBO.getDepthTexture();
            }
        } catch (Throwable t) {
            LOGGER.error("Error capturing current frame textures", t);
        }
    }

    public synchronized void advanceFrame() {
        if (ready) {
            // Swap previous and current buffers
            if (currentFrameFBO != null && previousFrameFBO != null) {
                TextureTarget temp = previousFrameFBO;
                previousFrameFBO = currentFrameFBO;
                currentFrameFBO = temp;
            }
            hasPreviousFrame = true;
            frameIndex++;
        }
    }

    public synchronized void reset() {
        hasPreviousFrame = false;
        frameIndex = 0;
    }

    public synchronized void destroy() {
        destroyBuffers();
        width = 0;
        height = 0;
        ready = false;
        hasPreviousFrame = false;
        frameIndex = 0;
    }

    private void destroyBuffers() {
        if (currentFrameFBO != null) {
            try { currentFrameFBO.destroyBuffers(); } catch (Throwable ignored) {}
            currentFrameFBO = null;
        }
        if (previousFrameFBO != null) {
            try { previousFrameFBO.destroyBuffers(); } catch (Throwable ignored) {}
            previousFrameFBO = null;
        }
        if (intermediateFrameFBO != null) {
            try { intermediateFrameFBO.destroyBuffers(); } catch (Throwable ignored) {}
            intermediateFrameFBO = null;
        }
        if (hudCompositeFBO != null) {
            try { hudCompositeFBO.destroyBuffers(); } catch (Throwable ignored) {}
            hudCompositeFBO = null;
        }
        if (entityMaskFBO != null) {
            try { entityMaskFBO.destroyBuffers(); } catch (Throwable ignored) {}
            entityMaskFBO = null;
        }
        if (worldOnlyFBO != null) {
            try { worldOnlyFBO.destroyBuffers(); } catch (Throwable ignored) {}
            worldOnlyFBO = null;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean hasPreviousFrame() {
        return hasPreviousFrame;
    }

    public long getFrameIndex() {
        return frameIndex;
    }

    public TextureTarget getCurrentFrameFBO() {
        return currentFrameFBO;
    }

    public TextureTarget getPreviousFrameFBO() {
        return previousFrameFBO;
    }

    public TextureTarget getIntermediateFrameFBO() {
        return intermediateFrameFBO;
    }

    public TextureTarget getHudCompositeFBO() {
        return hudCompositeFBO;
    }

    public TextureTarget getEntityMaskFBO() {
        return entityMaskFBO;
    }

    public TextureTarget getWorldOnlyFBO() {
        return worldOnlyFBO;
    }

    public com.mojang.blaze3d.textures.GpuTexture getLastDepthTexture() {
        return lastDepthTexture;
    }
}
