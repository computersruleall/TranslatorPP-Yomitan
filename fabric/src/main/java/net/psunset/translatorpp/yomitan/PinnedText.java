package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A frozen copy of some text (an item tooltip, a chat line, a sign) drawn as a
 * box that stays put, so the cursor can move over individual characters.
 */
public final class PinnedText {
    static final int PADDING = 4;
    static final int HEADER_HEIGHT = 11;
    private static final int MAX_WIDTH = 320;

    /** One visual line, broken into its characters so the cursor can be mapped onto them. */
    private record Glyphs(int[] codePoints, Style[] styles, int[] startX, int width) {
        String text(int from, int maxCodePoints) {
            StringBuilder builder = new StringBuilder();
            for (int i = from; i < codePoints.length && i < from + maxCodePoints; i++) {
                builder.appendCodePoint(codePoints[i]);
            }
            return builder.toString();
        }
    }

    /** A character under the cursor: its line, index and on-screen cell. */
    public record Hit(int line, int index, int x, int y, int width) {
    }

    private final List<FormattedCharSequence> lines;
    private final List<Glyphs> glyphs = new ArrayList<>();
    private final Component title;
    private final int width;
    private final int height;
    private int x;
    private int y;

    public PinnedText(Font font, Component title, List<Component> source, int anchorX, int anchorY, int screenWidth, int screenHeight) {
        this.title = title;
        this.lines = new ArrayList<>();
        for (Component line : source) {
            if (font.width(line) <= MAX_WIDTH) {
                lines.add(line.getVisualOrderText());
            } else {
                lines.addAll(font.split(line, MAX_WIDTH));
            }
        }
        int contentWidth = font.width(title);
        for (FormattedCharSequence line : lines) {
            Glyphs measured = measure(font, line);
            glyphs.add(measured);
            contentWidth = Math.max(contentWidth, measured.width());
        }
        this.width = contentWidth + PADDING * 2;
        this.height = HEADER_HEIGHT + lines.size() * font.lineHeight + PADDING * 2;
        moveTo(anchorX, anchorY, screenWidth, screenHeight);
    }

    private static Glyphs measure(Font font, FormattedCharSequence line) {
        List<Integer> codePoints = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        line.accept((index, style, codePoint) -> {
            codePoints.add(codePoint);
            styles.add(style);
            return true;
        });
        int[] cps = new int[codePoints.size()];
        int[] starts = new int[codePoints.size()];
        int cursor = 0;
        for (int i = 0; i < cps.length; i++) {
            cps[i] = codePoints.get(i);
            starts[i] = cursor;
            cursor += font.width(FormattedCharSequence.codepoint(cps[i], styles.get(i)));
        }
        return new Glyphs(cps, styles.toArray(Style[]::new), starts, cursor);
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
        Glyphs line = glyphs.get(row);
        int localX = (int) Math.floor(mouseX - (x + PADDING));
        if (localX < 0 || localX >= line.width()) {
            return null;
        }
        for (int i = line.codePoints().length - 1; i >= 0; i--) {
            if (localX >= line.startX()[i]) {
                if (Character.isWhitespace(line.codePoints()[i])) {
                    return null;
                }
                int cellWidth = (i + 1 < line.startX().length ? line.startX()[i + 1] : line.width()) - line.startX()[i];
                return new Hit(row, i, x + PADDING + line.startX()[i], textTop() + row * font.lineHeight, cellWidth);
            }
        }
        return null;
    }

    /** The text Yomitan should scan, starting at the hovered character. */
    public String queryAt(Hit hit, int maxCodePoints) {
        return glyphs.get(hit.line()).text(hit.index(), maxCodePoints);
    }

    /** Screen x of the end of a match that is {@code utf16Length} long, starting at the hit. */
    public int matchEndX(Hit hit, int utf16Length) {
        Glyphs line = glyphs.get(hit.line());
        int units = 0;
        int i = hit.index();
        while (i < line.codePoints().length && units < utf16Length) {
            units += Character.charCount(line.codePoints()[i]);
            i++;
        }
        int localEnd = i < line.startX().length ? line.startX()[i] : line.width();
        return x + PADDING + localEnd;
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
