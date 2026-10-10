package com.framegen.mixin;

import com.framegen.core.FramePacer;
import com.mojang.blaze3d.systems.GpuSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow @Final private GpuSurface windowSurface;
    @Shadow @Final private net.minecraft.client.renderer.GameRenderer gameRenderer;
    @Shadow private int frames;

    @Inject(
            method = "renderFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"
            )
    )
    private void framegen$onBeforeGameRendererRender(boolean renderLevel, CallbackInfo ci) {
        FramePacer.getInstance().recordRenderStart();
    }

    @Inject(
            method = "renderFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V",
                    shift = At.Shift.AFTER
            )
    )
    private void framegen$onAfterGameRendererRender(boolean renderLevel, CallbackInfo ci) {
        FramePacer.getInstance().recordRenderEnd();
        com.framegen.capture.FrameCaptureManager captureManager = com.framegen.capture.FrameCaptureManager.getInstance();
        try {
            if (captureManager.isCapturedThisFrame()) {
                com.mojang.blaze3d.pipeline.RenderTarget mainTarget = this.gameRenderer != null ? this.gameRenderer.mainRenderTarget() : null;
                if (FramePacer.getInstance().presentIntermediateFrame(this.windowSurface, mainTarget)) {
                    this.frames++;
                }
                captureManager.advanceFrame();
            }
        } finally {
            captureManager.setCapturedThisFrame(false);
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("FrameGenCadence");

    @Inject(
            method = "renderFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"
            )
    )
    private void framegen$onBeforeBasePresent(boolean renderLevel, CallbackInfo ci) {
        long intermTime = FramePacer.getInstance().getLastIntermediatePresentNanoTime();
        if (intermTime > 0) {
            long deltaNs = System.nanoTime() - intermTime;
            double deltaMs = deltaNs / 1_000_000.0;
            if (FramePacer.getInstance().getProbeFrameCounter() % 120 == 0) {
                double targetSpacing = FramePacer.getInstance().getBaseFps() > 0 ? (1000.0 / (2.0 * FramePacer.getInstance().getBaseFps())) : 16.67;
                LOGGER.info("[Cadence Probe] Intermediate present -> Base present delta: {:.3f} ms (Target half-step spacing: {:.2f} ms)",
                        deltaMs, targetSpacing);
            }
        }
    }
}
