package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A frozen copy of some text (a tooltip, a sign, a text display) drawn as a
 * box that stays put, so the cursor can move over individual characters.
 */
public final class PinnedText {
    static final int PADDING = 4;
    static final int HEADER_HEIGHT = 11;
    private static final int MAX_WIDTH = 320;

    /** A character under the cursor: its line, index and on-screen cell. */
    public record Hit(int line, int index, int x, int y, int width) {
    }

    private final List<FormattedCharSequence> lines;
    private final List<LineGlyphs> glyphs = new ArrayList<>();
    private final Component title;
    private final int width;
    private final int height;
    private int x;
    private int y;

    /** Pins plain components, wrapping any that are too wide. */
    public static PinnedText ofComponents(Font font, Component title, List<Component> source, int anchorX, int anchorY,
                                          int screenWidth, int screenHeight) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : source) {
            if (font.width(line) <= MAX_WIDTH) {
                lines.add(line.getVisualOrderText());
            } else {
                lines.addAll(font.split(line, MAX_WIDTH));
            }
        }
        return new PinnedText(font, title, lines, anchorX, anchorY, screenWidth, screenHeight);
    }

    /** Pins lines exactly as they were drawn (tooltips), keeping their styling. */
    public PinnedText(Font font, Component title, List<FormattedCharSequence> lines, int anchorX, int anchorY,
                      int screenWidth, int screenHeight) {
        this.title = title;
        this.lines = lines;
        int contentWidth = font.width(title);
        for (FormattedCharSequence line : lines) {
            LineGlyphs measured = LineGlyphs.measure(font, line);
            glyphs.add(measured);
            contentWidth = Math.max(contentWidth, measured.width());
        }
        this.width = contentWidth + PADDING * 2;
        this.height = HEADER_HEIGHT + lines.size() * font.lineHeight + PADDING * 2;
        moveTo(anchorX, anchorY, screenWidth, screenHeight);
    }

    public void moveTo(int newX, int newY, int screenWidth, int screenHeight) {
        this.x = Math.max(2, Math.min(newX, screenWidth - width - 2));
        this.y = Math.max(2, Math.min(newY, screenHeight - height - 2));
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public boolean onHeader(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) && mouseY < y + PADDING + HEADER_HEIGHT;
    }

    private int textTop() {
        return y + PADDING + HEADER_HEIGHT;
    }

    public @Nullable Hit hitTest(Font font, double mouseX, double mouseY) {
        int row = (int) Math.floor((mouseY - textTop()) / font.lineHeight);
        if (row < 0 || row >= glyphs.size()) {
            return null;
        }
        LineGlyphs line = glyphs.get(row);
        int index = line.indexAt((int) Math.floor(mouseX - (x + PADDING)));
        if (index < 0) {
            return null;
        }
        return new Hit(row, index, x + PADDING + line.startX()[index], textTop() + row * font.lineHeight, line.cellWidth(index));
    }

    /** The text Yomitan should scan, starting at the hovered character. */
    public String queryAt(Hit hit, int maxCodePoints) {
        return glyphs.get(hit.line()).text(hit.index(), maxCodePoints);
    }

    /** Screen x of the end of a match that is {@code utf16Length} long, starting at the hit. */
    public int matchEndX(Hit hit, int utf16Length) {
        return x + PADDING + glyphs.get(hit.line()).matchEndX(hit.index(), utf16Length);
    }

    public void extract(GuiGraphicsExtractor graphics, Font font) {
        Overlays.panel(graphics, x, y, width, height);
        graphics.text(font, title, x + PADDING, y + PADDING, 0xFF8A8AA0, false);
        int lineY = textTop();
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, x + PADDING, lineY, 0xFFFFFFFF, true);
            lineY += font.lineHeight;
        }
    }
}
