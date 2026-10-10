package com.framegen.mixin;

import com.framegen.capture.FrameCaptureManager;
import com.framegen.config.FrameGenConfig;
import com.framegen.core.CameraMatrixTracker;
import com.framegen.core.FramePacer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameGenCalibration");
    private static long framegen$calibrationCounter = 0;

    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow private RenderTarget mainRenderTarget;

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void framegen$onBeforeClearDepth(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.mainRenderTarget != null && this.gameRenderState != null && this.gameRenderState.levelRenderState != null) {
            CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
            if (cameraState != null && cameraState.initialized && cameraState.viewRotationMatrix != null && cameraState.projectionMatrix != null) {
                Vec3 pos = cameraState.pos != null ? cameraState.pos : Vec3.ZERO;
                Matrix4f viewMatrix = new Matrix4f(cameraState.viewRotationMatrix);
                Matrix4f projMatrix = new Matrix4f(cameraState.projectionMatrix);

                float deltaSeconds = deltaTracker != null ? Math.max(0.0001f, deltaTracker.getRealtimeDeltaTicks() / 20.0f) : (1.0f / 60.0f);
                CameraMatrixTracker.getInstance().updateMatrices(viewMatrix, projMatrix, pos.x, pos.y, pos.z, deltaSeconds);

                if (FrameGenConfig.getInstance().isDebugCalibrationLog() && (framegen$calibrationCounter++ % 120 == 0)) {
                    float m22 = projMatrix.m22();
                    float m32 = projMatrix.m32();
                    float distNearStd = m32 / (1.0f + m22);
                    float distNearRev = m32 / ((1.0f - 2.0f * 1.0f) + m22);
                    float distMidStd = m32 / (0.5f + m22);
                    float distMidRev = m32 / ((1.0f - 2.0f * 0.5f) + m22);
                    LOGGER.info("[Reverse-Z Calibration] Proj m22={}, m32={}. [z=d]: d=1.0->{}, d=0.5->{} | [z=1-2d]: d=1.0->{}, d=0.5->{}",
                            m22, m32, distNearStd, distMidStd, distNearRev, distMidRev);
                }

                FrameCaptureManager captureManager = FrameCaptureManager.getInstance();
                boolean captured = captureManager.captureCurrentFrame(this.mainRenderTarget);
                captureManager.setCapturedThisFrame(captured);

                if (captured) {
                    FramePacer.getInstance().onRenderTick(this.mainRenderTarget, CameraMatrixTracker.getInstance(), captureManager);
                }
            }
        }
    }
}

