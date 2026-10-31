package net.psunset.translatorpp.core;

import net.minecraft.network.chat.Component;
import net.psunset.translatorpp.api.ComponentizableEnum;
import org.jetbrains.annotations.NotNull;

/**
 * How chat gets translated. The hover key works in both.
 */
public enum ChatTranslationMode implements ComponentizableEnum {
    /** Only when the player holds the translate key over a message. */
    HOVER("Hover Key (On Demand)"),
    /** Messages not in the target language are replaced by their translation as they arrive. */
    AUTO("Automatic (Replace)");

    private final String displayName;

    ChatTranslationMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public @NotNull Component toComponent() {
        return Component.literal(displayName);
    }
}
