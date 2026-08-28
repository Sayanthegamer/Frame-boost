package com.framegen.mixin;

import com.framegen.capture.FrameCaptureManager;
import com.framegen.core.CameraMatrixTracker;
import com.framegen.core.FramePacer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow private RenderTarget mainRenderTarget;

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void framegen$onRenderLevelReturn(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.mainRenderTarget != null && this.gameRenderState != null && this.gameRenderState.levelRenderState != null) {
            CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
            if (cameraState != null && cameraState.initialized && cameraState.viewRotationMatrix != null && cameraState.projectionMatrix != null) {
                Vec3 pos = cameraState.pos != null ? cameraState.pos : Vec3.ZERO;
                Matrix4f viewMatrix = new Matrix4f(cameraState.viewRotationMatrix);
                Matrix4f projMatrix = new Matrix4f(cameraState.projectionMatrix);

                CameraMatrixTracker.getInstance().updateMatrices(viewMatrix, projMatrix, pos.x, pos.y, pos.z);

                FrameCaptureManager captureManager = FrameCaptureManager.getInstance();
                captureManager.captureCurrentFrame(this.mainRenderTarget);

                FramePacer.getInstance().onRenderTick(this.mainRenderTarget, CameraMatrixTracker.getInstance(), captureManager);
                captureManager.advanceFrame();
            }
        }
    }
}

