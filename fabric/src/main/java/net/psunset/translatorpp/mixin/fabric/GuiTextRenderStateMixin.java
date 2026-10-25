package net.psunset.translatorpp.mixin.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import net.psunset.translatorpp.yomitan.TextCapture;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every piece of GUI text becomes one of these, whoever draws it. Capturing at
 * construction rather than at {@code GuiRenderState.addText} is deliberate:
 * under Lunar Client nothing reaches an injection there, while its own text
 * code still builds these.
 */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextRenderStateMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void translatorpp$captureText(Font font, FormattedCharSequence text, Matrix3x2fc pose, int x, int y,
                                         int color, int backgroundColor, boolean dropShadow, boolean includeEmpty,
                                         ScreenRectangle scissor, CallbackInfo ci) {
        TextCapture.onText((GuiTextRenderState) (Object) this);
    }
}
