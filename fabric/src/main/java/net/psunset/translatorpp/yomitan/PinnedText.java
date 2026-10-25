package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
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
    /** Drag selection as {line, boundary} ends; null when nothing is selected. */
    private int @Nullable [] selectionAnchor;
    private int @Nullable [] selectionCaret;

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

    /** Whether the cursor is over the text rows, where dragging selects rather than moves the box. */
    public boolean onText(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) && !onHeader(mouseX, mouseY);
    }

    private int[] caretAt(Font font, double mouseX, double mouseY) {
        int row = (int) Math.floor((mouseY - textTop()) / font.lineHeight);
        row = Math.max(0, Math.min(row, glyphs.size() - 1));
        return new int[]{row, glyphs.get(row).boundaryAt((int) Math.floor(mouseX - (x + PADDING)))};
    }

    public void startSelection(Font font, double mouseX, double mouseY) {
        if (glyphs.isEmpty()) {
            return;
        }
        selectionAnchor = caretAt(font, mouseX, mouseY);
        selectionCaret = selectionAnchor;
    }

    public void extendSelection(Font font, double mouseX, double mouseY) {
        if (selectionAnchor != null) {
            selectionCaret = caretAt(font, mouseX, mouseY);
        }
    }

    public boolean hasSelection() {
        return selectionAnchor != null && selectionCaret != null && !Arrays.equals(selectionAnchor, selectionCaret);
    }

    /** The selection's ends in reading order. */
    private int[][] orderedSelection() {
        boolean forward = selectionAnchor[0] < selectionCaret[0]
                || (selectionAnchor[0] == selectionCaret[0] && selectionAnchor[1] <= selectionCaret[1]);
        return forward ? new int[][]{selectionAnchor, selectionCaret} : new int[][]{selectionCaret, selectionAnchor};
    }

    /** The selected text, lines joined with newlines. */
    public String selectedText() {
        if (!hasSelection()) {
            return "";
        }
        int[][] range = orderedSelection();
        StringBuilder builder = new StringBuilder();
        for (int line = range[0][0]; line <= range[1][0]; line++) {
            LineGlyphs row = glyphs.get(line);
            int from = line == range[0][0] ? range[0][1] : 0;
            int to = line == range[1][0] ? range[1][1] : row.codePoints().length;
            if (line > range[0][0]) {
                builder.append('\n');
            }
            builder.append(row.text(from, to - from));
        }
        return builder.toString();
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
        if (hasSelection()) {
            int[][] range = orderedSelection();
            for (int line = range[0][0]; line <= range[1][0]; line++) {
                LineGlyphs row = glyphs.get(line);
                int from = line == range[0][0] ? range[0][1] : 0;
                int to = line == range[1][0] ? range[1][1] : row.codePoints().length;
                int top = textTop() + line * font.lineHeight;
                graphics.fill(x + PADDING + row.boundaryX(from), top - 1, x + PADDING + row.boundaryX(to) + (to == from ? 2 : 0),
                        top + font.lineHeight - 1, 0xA02F6FD0);
            }
        }
        int lineY = textTop();
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, x + PADDING, lineY, 0xFFFFFFFF, true);
            lineY += font.lineHeight;
        }
    }
}
