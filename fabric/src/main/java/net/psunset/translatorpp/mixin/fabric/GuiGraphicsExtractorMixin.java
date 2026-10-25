package net.psunset.translatorpp.mixin.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.psunset.translatorpp.yomitan.TextCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Marks text drawn as part of a tooltip, so the pin key can freeze exactly that tooltip. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    @Inject(method = "tooltip", at = @At("HEAD"))
    private void translatorpp$beginTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                          ClientTooltipPositioner positioner, Identifier background, boolean focused,
                                          CallbackInfo ci) {
        TextCapture.beginTooltip();
    }

    @Inject(method = "tooltip", at = @At("RETURN"))
    private void translatorpp$endTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                        ClientTooltipPositioner positioner, Identifier background, boolean focused,
                                        CallbackInfo ci) {
        TextCapture.endTooltip();
    }
}
