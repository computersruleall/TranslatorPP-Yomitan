package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * One drawn line broken into its characters, with each character's x offset,
 * so a cursor position can be mapped onto the character under it.
 */
record LineGlyphs(int[] codePoints, int[] startX, int width) {

    static LineGlyphs measure(Font font, FormattedCharSequence line) {
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
        return new LineGlyphs(cps, starts, cursor);
    }

    /** Index of the character at {@code localX}, or -1 for empty space and whitespace. */
    int indexAt(int localX) {
        if (localX < 0 || localX >= width) {
            return -1;
        }
        for (int i = codePoints.length - 1; i >= 0; i--) {
            if (localX >= startX[i]) {
                return Character.isWhitespace(codePoints[i]) ? -1 : i;
            }
        }
        return -1;
    }

    String text(int from, int maxCodePoints) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < codePoints.length && i < from + maxCodePoints; i++) {
            builder.appendCodePoint(codePoints[i]);
        }
        return builder.toString();
    }

    String text() {
        return text(0, codePoints.length);
    }

    int cellWidth(int index) {
        return (index + 1 < startX.length ? startX[index + 1] : width) - startX[index];
    }

    /** Local x of the end of a match that is {@code utf16Length} long, starting at {@code from}. */
    int matchEndX(int from, int utf16Length) {
        int units = 0;
        int i = from;
        while (i < codePoints.length && units < utf16Length) {
            units += Character.charCount(codePoints[i]);
            i++;
        }
        return i < startX.length ? startX[i] : width;
    }
}
