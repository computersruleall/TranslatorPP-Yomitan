package net.psunset.translatorpp.mixin.fabric;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.psunset.translatorpp.yomitan.TextCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each frame starts with {@code GameRenderer.extract}, which is where the
 * previous frame's captured text is closed. The GUI render state's own reset
 * can't mark frames: under Lunar it runs more than once per frame.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void translatorpp$startFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        TextCapture.endFrame();
    }
}
