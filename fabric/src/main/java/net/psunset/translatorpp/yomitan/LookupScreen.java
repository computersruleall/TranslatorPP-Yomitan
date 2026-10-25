package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Holds text picked from the world (a sign, text display or nametag) in a
 * pinned box. Opening a screen frees the cursor, which is what makes the
 * per-character lookup possible for world text at all.
 */
public final class LookupScreen extends Screen {
    private final Component source;
    private final List<Component> lines;

    public LookupScreen(Component source, List<Component> lines) {
        super(Component.literal("Dictionary lookup"));
        this.source = source;
        this.lines = lines;
    }

    @Override
    protected void init() {
        YomitanOverlay.pin(source, lines, 0, 0);
        YomitanOverlay.centerPinned();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
