package net.psunset.translatorpp.yomitan;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.psunset.translatorpp.keybind.TPPKeyMappings;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Opened by the pin key during play, to free the cursor. With world text (a
 * sign, text display or nametag) it shows that text pinned; without, it is a
 * transparent layer so the HUD behind it (scoreboard, boss bars, titles, chat)
 * can be hovered like any other text.
 */
public final class LookupScreen extends Screen {
    private final @Nullable Component source;
    private final List<Component> lines;

    /** Frees the cursor over the HUD. */
    public LookupScreen() {
        this(null, List.of());
    }

    public LookupScreen(@Nullable Component source, List<Component> lines) {
        super(Component.literal("Dictionary lookup"));
        this.source = source;
        this.lines = lines;
    }

    @Override
    protected void init() {
        if (source != null) {
            YomitanOverlay.pin(source, lines, 0, 0);
            YomitanOverlay.centerPinned();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No dimming or blur: the HUD behind this screen is part of what can be looked up.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (source == null) {
            Component hint = Component.literal("Hold ").append(TPPKeyMappings.LOOKUP_KEY.getTranslatedKeyMessage())
                    .append(" over any text to look it up · Esc to close").withStyle(ChatFormatting.GRAY);
            TextCapture.runUncaptured(() -> graphics.centeredText(font, hint, width / 2, 6, 0xFFFFFFFF));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
