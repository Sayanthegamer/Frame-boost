package com.framegen.mixin;

import com.framegen.capture.FrameCaptureManager;
import com.framegen.core.CameraMatrixTracker;
import com.framegen.core.FramePacer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
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

    @Shadow @Final private Camera mainCamera;
    @Shadow private RenderTarget mainRenderTarget;

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void framegen$onRenderLevelReturn(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.mainRenderTarget != null && this.mainCamera != null && this.mainCamera.isInitialized()) {
            Vec3 pos = this.mainCamera.position();
            Matrix4f viewMatrix = this.mainCamera.getViewRotationMatrix(new Matrix4f());
            Matrix4f projMatrix = RenderSystem.getModelViewMatrixCopy();

            CameraMatrixTracker.getInstance().updateMatrices(viewMatrix, projMatrix, pos.x, pos.y, pos.z);

            FrameCaptureManager captureManager = FrameCaptureManager.getInstance();
            captureManager.captureCurrentFrame(this.mainRenderTarget);

            FramePacer.getInstance().onRenderTick(this.mainRenderTarget, CameraMatrixTracker.getInstance(), captureManager);
            captureManager.advanceFrame();
        }
    }
}
