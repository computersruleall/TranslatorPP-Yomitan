package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared drawing for the pinned box and the dictionary popup, styled like a vanilla tooltip. */
final class Overlays {
    private static final int BACKGROUND = 0xF0100010;
    private static final int BORDER_TOP = 0x505000FF;
    private static final int BORDER_BOTTOM = 0x5028007F;

    private Overlays() {
    }

    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.fillGradient(x, y, x + 1, y + height, BORDER_TOP, BORDER_BOTTOM);
        graphics.fillGradient(x + width - 1, y, x + width, y + height, BORDER_TOP, BORDER_BOTTOM);
        graphics.fill(x, y, x + width, y + 1, BORDER_TOP);
        graphics.fill(x, y + height - 1, x + width, y + height, BORDER_BOTTOM);
    }
}
