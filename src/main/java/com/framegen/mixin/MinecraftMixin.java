package com.framegen.mixin;

import com.framegen.config.FrameGenConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow private int frames;

    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V",
                    shift = At.Shift.AFTER
            )
    )
    private void framegen$onPresentFrame(boolean renderLevel, CallbackInfo ci) {
        if (FrameGenConfig.getInstance().isEnabled()) {
            this.frames++;
        }
    }
}
