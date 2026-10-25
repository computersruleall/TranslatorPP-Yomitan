package net.psunset.translatorpp.mixin.fabric;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.psunset.translatorpp.yomitan.TextCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every piece of GUI text passes through here, which is what makes lookups work on any text. */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    @Inject(method = "addText", at = @At("HEAD"))
    private void translatorpp$captureText(GuiTextRenderState text, CallbackInfo ci) {
        TextCapture.onText(this, text);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void translatorpp$endFrame(CallbackInfo ci) {
        TextCapture.endFrame(this);
    }
}
