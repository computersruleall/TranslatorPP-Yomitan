package net.psunset.translatorpp.mixin.fabric;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.psunset.translatorpp.yomitan.YomitanOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slots under the pinned box or dictionary popup are not "hovered", so their
 * tooltip and highlight don't draw over the box.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "getHoveredSlot", at = @At("HEAD"), cancellable = true)
    private void translatorpp$hideSlotsUnderOverlay(double mouseX, double mouseY, CallbackInfoReturnable<Slot> cir) {
        if (YomitanOverlay.isMouseOver(mouseX, mouseY)) {
            cir.setReturnValue(null);
        }
    }
}
