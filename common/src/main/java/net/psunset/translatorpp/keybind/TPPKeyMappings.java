package net.psunset.translatorpp.keybind;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.psunset.translatorpp.tool.IdUtl;

public final class TPPKeyMappings {

    private static final ImmutableSet.Builder<KeyMapping> ENTRIES = ImmutableSet.builder();

    public static final KeyMapping TRANSLATE_KEY = register(
            "key.translatorpp.translate",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_T,
            Categories.GENERAL
    );

    public static KeyMapping CONFIG_KEY = register(
            "key.translatorpp.config",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_O,
            Categories.GENERAL
    );

    /** Shift + middle click by default; plain middle click is left to menus and creative. */
    public static final KeyMapping PIN_KEY = register(
            "key.translatorpp.pin",
            InputConstants.Type.MOUSE,
            InputConstants.MOUSE_BUTTON_MIDDLE,
            Categories.GENERAL
    );

    /** Held while hovering pinned text to look it up in Yomitan. Separate from {@link #TRANSLATE_KEY} on purpose. */
    public static final KeyMapping LOOKUP_KEY = register(
            "key.translatorpp.lookup",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_LSHIFT,
            Categories.GENERAL
    );

    /** Toggles a free cursor during play, to hover HUD text (action bar, boss bars, scoreboard). */
    public static final KeyMapping FREE_CURSOR_KEY = register(
            "key.translatorpp.free_cursor",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_LALT,
            Categories.GENERAL
    );

    private static KeyMapping register(String translation, InputConstants.Type type, int keyCode, KeyMapping.Category category) {
        var key = new KeyMapping(translation, type, keyCode, category);
        ENTRIES.add(key);
        return key;
    }

    private static KeyMapping register(String translation, int keyCode, KeyMapping.Category category) {
        var key = new KeyMapping(translation, keyCode, category);
        ENTRIES.add(key);
        return key;
    }

    public static ImmutableSet<KeyMapping> getEntries() {
        return ENTRIES.build();
    }

    interface Categories {
        KeyMapping.Category GENERAL = KeyMapping.Category.register(IdUtl.of("general"));
    }
}
