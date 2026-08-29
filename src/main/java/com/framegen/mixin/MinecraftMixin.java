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
                    target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V",
                    shift = At.Shift.AFTER
            )
    )
    private void framegen$onAfterGameRendererRender(boolean renderLevel, CallbackInfo ci) {
        com.mojang.blaze3d.pipeline.RenderTarget mainTarget = this.gameRenderer != null ? this.gameRenderer.mainRenderTarget() : null;
        if (FramePacer.getInstance().presentIntermediateFrame(this.windowSurface, mainTarget)) {
            this.frames++;
        }
    }
}
