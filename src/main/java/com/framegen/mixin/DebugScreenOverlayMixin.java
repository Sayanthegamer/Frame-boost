package com.framegen.mixin;

import com.framegen.config.FrameGenConfig;
import com.framegen.core.FramePacer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {

    @Inject(method = "extractLines", at = @At("HEAD"))
    private void framegen$addTelemetryToDebugScreen(GuiGraphicsExtractor extractor, List<String> list, boolean left, CallbackInfo ci) {
        if (left) {
            FrameGenConfig config = FrameGenConfig.getInstance();
            if (config.isDebugOverlayEnabled()) {
                String telemetry = FramePacer.getInstance().getTelemetryString(config.isEnabled());
                list.removeIf(s -> s.contains("[FrameGen 2x]"));
                list.add(telemetry);
            }
        }
    }
}
