package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.psunset.translatorpp.config.TPPConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The dictionary popup, laid out like Yomitan's browser popup in its dark
 * theme: a large headword with furigana over the kanji, coloured tag pills,
 * numbered senses with ◦ glosses, and every entry from every dictionary in one
 * scrollable panel.
 */
public final class LookupPopup {
    private static final int PADDING = 6;
    private static final int INDENT = 10;
    private static final int SCROLL_STEP = 30;
    private static final int TERM_SCALE = 2;

    private static final int BACKGROUND = 0xF61E1E1E;
    private static final int BORDER = 0xFF3C3C3C;
    private static final int DIVIDER = 0xFF343434;
    private static final int TERM_COLOR = 0xFFF0F0F0;
    private static final int READING_COLOR = 0xFFC8C8C8;
    private static final int GLOSS_COLOR = 0xFFE2E2E2;
    private static final int MUTED_COLOR = 0xFFA0A0A0;
    private static final int PRIMARY_MARKER = 0xFF4A9EFF;
    private static final int PILL_TEXT = 0xFFFFFFFF;

    /** Something drawn in the popup, stacked top to bottom. */
    private interface Row {
        int height();

        void draw(GuiGraphicsExtractor graphics, Font font, int x, int y);
    }

    private record TextRow(int offset, FormattedCharSequence text, int color, int height) implements Row {
        @Override
        public void draw(GuiGraphicsExtractor graphics, Font font, int x, int y) {
            graphics.text(font, text, x + offset, y, color, false);
        }
    }

    /** Vertical space; dividers are drawn by the popup, which knows its width. */
    private record SpacerRow(int height, boolean divider) implements Row {
        @Override
        public void draw(GuiGraphicsExtractor graphics, Font font, int x, int y) {
        }
    }

    /** A headword: furigana above the kanji, the term at double size, its tags beside it. */
    private record HeadRow(String term, String reading, List<TermEntry.Tag> tags, boolean primary) implements Row {
        private static final int FURIGANA_HEIGHT = 10;

        @Override
        public int height() {
            return FURIGANA_HEIGHT + 9 * TERM_SCALE + 4;
        }

        @Override
        public void draw(GuiGraphicsExtractor graphics, Font font, int x, int y) {
            int termY = y + FURIGANA_HEIGHT;
            if (primary) {
                // Yomitan's ▶ marker on the entry that best matches the scanned text.
                int midY = termY + 8;
                for (int i = 0; i < 4; i++) {
                    graphics.fill(x - PADDING + 1, midY - 4 + i, x - PADDING + 2 + i, midY + 4 - i, PRIMARY_MARKER);
                }
            }

            Furigana furigana = Furigana.of(term, reading);
            if (furigana != null) {
                int startX = x + font.width(furigana.before()) * TERM_SCALE;
                int spanWidth = font.width(furigana.base()) * TERM_SCALE;
                int readingX = startX + (spanWidth - font.width(furigana.reading())) / 2;
                graphics.text(font, furigana.reading(), readingX, y, READING_COLOR, false);
            }

            graphics.pose().pushMatrix();
            graphics.pose().translate(x, termY);
            graphics.pose().scale(TERM_SCALE, TERM_SCALE);
            graphics.text(font, term, 0, 0, TERM_COLOR, false);
            graphics.pose().popMatrix();

            int termWidth = font.width(term) * TERM_SCALE;
            // Yomitan's dotted underline under the headword.
            for (int dotX = x; dotX < x + termWidth; dotX += 3) {
                graphics.fill(dotX, termY + 9 * TERM_SCALE + 1, dotX + 1, termY + 9 * TERM_SCALE + 2, MUTED_COLOR);
            }

            int pillX = x + termWidth + 6;
            for (TermEntry.Tag tag : tags) {
                pillX += Pill.draw(graphics, font, tag.name(), Pill.colorOf(tag.category()), pillX, termY + 4) + 3;
            }
        }
    }

    /** One line of pills (tags, the dictionary name), with an optional "1." sense number in front. */
    private record PillRow(String number, List<String> labels, List<Integer> colors) implements Row {
        @Override
        public int height() {
            return Pill.HEIGHT + 3;
        }

        @Override
        public void draw(GuiGraphicsExtractor graphics, Font font, int x, int y) {
            int pillX = x;
            if (!number.isEmpty()) {
                graphics.text(font, number, pillX, y + 2, MUTED_COLOR, false);
                pillX += font.width(number) + 4;
            }
            for (int i = 0; i < labels.size(); i++) {
                pillX += Pill.draw(graphics, font, labels.get(i), colors.get(i), pillX, y) + 3;
            }
        }
    }

    private static final class Pill {
        static final int HEIGHT = 11;

        /** Draws a rounded pill and returns its width. */
        static int draw(GuiGraphicsExtractor graphics, Font font, String label, int color, int x, int y) {
            int width = font.width(label) + 6;
            graphics.fill(x + 1, y, x + width - 1, y + HEIGHT, color);
            graphics.fill(x, y + 1, x + width, y + HEIGHT - 1, color);
            graphics.text(font, label, x + 3, y + 2, PILL_TEXT, false);
            return width;
        }

        static int width(Font font, String label) {
            return font.width(label) + 6;
        }

        /** Colours of Yomitan's dark-theme tag categories. */
        static int colorOf(String category) {
            return switch (category) {
                case "popular" -> 0xFF0B5CBF;
                case "frequent" -> 0xFF3C8C9B;
                case "partOfSpeech" -> 0xFF585858;
                case "name" -> 0xFFB02468;
                case "archaism" -> 0xFF8E5A2B;
                case "expression" -> 0xFFC2721D;
                case "dictionary" -> 0xFF9B59B6;
                case "frequency" -> 0xFF5A7D2C;
                default -> 0xFF6A6A70;
            };
        }
    }

    /** Where the reading sits over the term: the kanji span between any shared kana prefix and suffix. */
    private record Furigana(String before, String base, String reading) {
        static Furigana of(String term, String reading) {
            if (reading.isEmpty() || reading.equals(term)) {
                return null;
            }
            int prefix = 0;
            while (prefix < term.length() && prefix < reading.length() && term.charAt(prefix) == reading.charAt(prefix)) {
                prefix++;
            }
            int suffix = 0;
            while (suffix < term.length() - prefix && suffix < reading.length() - prefix
                    && term.charAt(term.length() - 1 - suffix) == reading.charAt(reading.length() - 1 - suffix)) {
                suffix++;
            }
            String base = term.substring(prefix, term.length() - suffix);
            String baseReading = reading.substring(prefix, reading.length() - suffix);
            if (base.isEmpty() || baseReading.isEmpty()) {
                return new Furigana("", term, reading);
            }
            return new Furigana(term.substring(0, prefix), base, baseReading);
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final int width = Math.max(160, TPPConfig.getInstance().getYomitanPopupWidth());
    private final int maxHeight = Math.max(80, TPPConfig.getInstance().getYomitanPopupMaxHeight());
    private int x;
    private int y;
    private int height;
    private int contentHeight;
    private double scroll;

    private LookupPopup() {
    }

    static LookupPopup message(Font font, Component message, int anchorX, int anchorY, int screenWidth, int screenHeight) {
        LookupPopup popup = new LookupPopup();
        popup.addText(font, message, 0, GLOSS_COLOR);
        popup.layout(anchorX, anchorY, screenWidth, screenHeight);
        return popup;
    }

    static LookupPopup results(Font font, List<TermEntry> entries, int anchorX, int anchorY, int screenWidth, int screenHeight) {
        LookupPopup popup = new LookupPopup();
        for (int e = 0; e < entries.size(); e++) {
            TermEntry entry = entries.get(e);
            if (e > 0) {
                popup.rows.add(new SpacerRow(9, true));
            }
            for (TermEntry.Headword headword : entry.headwords()) {
                popup.rows.add(new HeadRow(headword.term(), headword.reading(), headword.tags(), e == 0));
            }
            for (List<String> chain : entry.inflections()) {
                popup.addText(font, Component.literal("« " + String.join(" « ", chain)), 0, MUTED_COLOR);
            }
            popup.rows.add(new SpacerRow(2, false));

            boolean numbered = entry.definitions().size() > 1;
            for (int d = 0; d < entry.definitions().size(); d++) {
                TermEntry.Definition definition = entry.definitions().get(d);
                List<String> labels = new ArrayList<>();
                List<Integer> colors = new ArrayList<>();
                for (TermEntry.Tag tag : definition.tags()) {
                    labels.add(tag.name());
                    colors.add(Pill.colorOf(tag.category()));
                }
                labels.add(definition.dictionary());
                colors.add(Pill.colorOf("dictionary"));
                popup.addPills(font, numbered ? (d + 1) + "." : "", labels, colors);
                int offset = numbered ? INDENT : 0;
                for (GlossaryRenderer.Line line : definition.lines()) {
                    popup.addText(font, line.text(), offset + Math.min(line.indent(), 3) * INDENT, GLOSS_COLOR);
                }
                popup.rows.add(new SpacerRow(3, false));
            }
        }
        popup.layout(anchorX, anchorY, screenWidth, screenHeight);
        return popup;
    }

    private void addText(Font font, Component text, int offset, int color) {
        for (FormattedCharSequence line : font.split(text, width - PADDING * 2 - offset)) {
            rows.add(new TextRow(offset, line, color, font.lineHeight + 1));
        }
    }

    /** Pills wrap onto further rows when they don't fit; the sense number only leads the first. */
    private void addPills(Font font, String number, List<String> labels, List<Integer> colors) {
        int available = width - PADDING * 2;
        int used = number.isEmpty() ? 0 : font.width(number) + 4;
        List<String> rowLabels = new ArrayList<>();
        List<Integer> rowColors = new ArrayList<>();
        String rowNumber = number;
        for (int i = 0; i < labels.size(); i++) {
            int width = Pill.width(font, labels.get(i)) + 3;
            if (used + width > available && !rowLabels.isEmpty()) {
                rows.add(new PillRow(rowNumber, rowLabels, rowColors));
                rowLabels = new ArrayList<>();
                rowColors = new ArrayList<>();
                rowNumber = "";
                used = 0;
            }
            rowLabels.add(labels.get(i));
            rowColors.add(colors.get(i));
            used += width;
        }
        if (!rowLabels.isEmpty() || !rowNumber.isEmpty()) {
            rows.add(new PillRow(rowNumber, rowLabels, rowColors));
        }
    }

    private void layout(int anchorX, int anchorY, int screenWidth, int screenHeight) {
        contentHeight = rows.stream().mapToInt(Row::height).sum();
        height = Math.min(contentHeight + PADDING * 2, Math.min(maxHeight, screenHeight - 8));
        x = Mth.clamp(anchorX, 4, Math.max(4, screenWidth - width - 4));
        // Below the hovered character if it fits, otherwise above it.
        y = anchorY + height + 4 <= screenHeight ? anchorY : Math.max(4, anchorY - 12 - height);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public void scroll(double amount) {
        int maxScroll = Math.max(0, contentHeight - (height - PADDING * 2));
        scroll = Mth.clamp(scroll - amount * SCROLL_STEP, 0, maxScroll);
    }

    public void extract(GuiGraphicsExtractor graphics, Font font) {
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.outline(x, y, width, height, BORDER);

        int top = y + PADDING;
        int bottom = y + height - PADDING;
        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        int rowY = top - (int) scroll;
        for (Row row : rows) {
            if (rowY + row.height() >= y && rowY <= bottom) {
                if (row instanceof SpacerRow spacer && spacer.divider()) {
                    int lineY = rowY + spacer.height() / 2;
                    graphics.fill(x + 1, lineY, x + width - 1, lineY + 1, DIVIDER);
                }
                row.draw(graphics, font, x + PADDING, rowY);
            }
            rowY += row.height();
        }
        graphics.disableScissor();

        int viewport = height - PADDING * 2;
        if (contentHeight > viewport) {
            int barHeight = Math.max(10, viewport * viewport / contentHeight);
            int barY = top + (int) ((viewport - barHeight) * (scroll / (contentHeight - viewport)));
            graphics.fill(x + width - 4, barY, x + width - 2, barY + barHeight, 0xFF6A6A6A);
        }
    }
}
