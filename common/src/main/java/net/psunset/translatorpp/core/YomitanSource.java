package net.psunset.translatorpp.core;

import net.minecraft.network.chat.Component;
import net.psunset.translatorpp.api.ComponentizableEnum;
import org.jetbrains.annotations.NotNull;

/**
 * Where dictionary lookups are answered.
 */
public enum YomitanSource implements ComponentizableEnum {
    /** The mod's own engine, using dictionaries imported into the game. */
    BUILT_IN("Built-in Dictionaries"),
    /** The Yomitan browser extension, through yomitan-api. */
    BROWSER("Yomitan in Browser");

    private final String displayName;

    YomitanSource(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public @NotNull Component toComponent() {
        return Component.literal(displayName);
    }
}
