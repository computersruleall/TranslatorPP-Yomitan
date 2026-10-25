package net.psunset.translatorpp.mixin.fabric;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.psunset.translatorpp.yomitan.TextCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The GUI render state is reset once per frame, after drawing: that closes a frame of captured text. */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    @Inject(method = "reset", at = @At("HEAD"))
    private void translatorpp$endFrame(CallbackInfo ci) {
        TextCapture.endFrame();
    }
}
