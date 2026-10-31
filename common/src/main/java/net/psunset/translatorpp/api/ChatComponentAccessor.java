package net.psunset.translatorpp.api;

import net.psunset.translatorpp.mixin.ChatComponentMixin;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * To access mixin methods in ChatComponent.
 */
public interface ChatComponentAccessor {

    /**
     * @see ChatComponentMixin#translatorpp$getMessageContentAt(double, double)
     */
    @Nullable String translatorpp$getMessageContentAt(double globalMouseX, double globalMouseY);

    /**
     * @see ChatComponentMixin#translatorpp$messageIndexTrimmedToAll
     */
    int[] translatorpp$getMessageIndexTrimmedToAll();

    double translatorpp$screenToChatX(double x);

    double translatorpp$screenToChatY(double y);

    int translatorpp$getMessageLineIndexAt(double mouseX, double mouseY);

    /** The wrapped chat line under the cursor, as drawn, or null. Takes screen coordinates. */
    @Nullable FormattedCharSequence translatorpp$getLineContentAt(double globalMouseX, double globalMouseY);

    double translatorpp$getChatScale();

    int translatorpp$getChatLineHeight();

    /** Swaps a message already in chat for another, keeping its place and the scroll position. */
    void translatorpp$replaceMessage(GuiMessage original, GuiMessage replacement);
}
