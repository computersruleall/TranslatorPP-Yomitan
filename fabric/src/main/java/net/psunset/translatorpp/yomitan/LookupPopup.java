package net.psunset.translatorpp.yomitan;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The dictionary popup: every entry Yomitan returned, from every dictionary,
 * in one scrollable panel, the way the browser popup lists them.
 */
public final class LookupPopup {
    private static final int WIDTH = 270;
    private static final int PADDING = 5;
    private static final int INDENT = 8;
    private static final int MAX_HEIGHT = 230;
    private static final int SCROLL_STEP = 27;

    /** A drawn row: horizontal offset inside the panel, the text, and a divider flag. */
    private record Row(int offset, FormattedCharSequence text, boolean divider) {
    }

    private final List<Row> rows = new ArrayList<>();
    private int x;
    private int y;
    private int height;
    private int contentHeight;
    private double scroll;

    private LookupPopup() {
    }

    static LookupPopup message(Font font, Component message, int anchorX, int anchorY, int screenWidth, int screenHeight) {
        LookupPopup popup = new LookupPopup();
        for (FormattedCharSequence line : font.split(message, WIDTH - PADDING * 2)) {
            popup.rows.add(new Row(0, line, false));
        }
        popup.layout(font, anchorX, anchorY, screenWidth, screenHeight);
        return popup;
    }

    static LookupPopup results(Font font, List<TermEntry> entries, int anchorX, int anchorY, int screenWidth, int screenHeight) {
        LookupPopup popup = new LookupPopup();
        int textWidth = WIDTH - PADDING * 2;
        for (int e = 0; e < entries.size(); e++) {
            TermEntry entry = entries.get(e);
            if (e > 0) {
                popup.rows.add(new Row(0, FormattedCharSequence.EMPTY, true));
            }
            MutableComponent heading = Component.literal(String.join("・", entry.headwords()))
                    .withStyle(ChatFormatting.YELLOW);
            popup.add(font, heading, 0, textWidth);
            for (List<String> chain : entry.inflections()) {
                popup.add(font, Component.literal("« " + String.join(" « ", chain))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), 0, textWidth);
            }
            String lastDictionary = null;
            for (TermEntry.Definition definition : entry.definitions()) {
                if (!definition.dictionary().equals(lastDictionary) || !definition.tags().isEmpty()) {
                    MutableComponent label = Component.empty();
                    if (!definition.dictionary().equals(lastDictionary)) {
                        label.append(Component.literal(definition.dictionary()).withStyle(ChatFormatting.LIGHT_PURPLE));
                    }
                    for (String tag : definition.tags()) {
                        label.append(Component.literal(" " + tag).withStyle(ChatFormatting.DARK_AQUA));
                    }
                    popup.add(font, label, 0, textWidth);
                    lastDictionary = definition.dictionary();
                }
                for (GlossaryRenderer.Line line : definition.lines()) {
                    int offset = Math.min(line.indent(), 4) * INDENT;
                    popup.add(font, line.text(), offset, textWidth - offset);
                }
            }
        }
        popup.layout(font, anchorX, anchorY, screenWidth, screenHeight);
        return popup;
    }

    private void add(Font font, Component text, int offset, int width) {
        for (FormattedCharSequence line : font.split(text, width)) {
            rows.add(new Row(offset, line, false));
        }
    }

    private void layout(Font font, int anchorX, int anchorY, int screenWidth, int screenHeight) {
        contentHeight = rows.size() * font.lineHeight;
        height = Math.min(contentHeight + PADDING * 2, Math.min(MAX_HEIGHT, screenHeight - 8));
        x = Mth.clamp(anchorX, 4, Math.max(4, screenWidth - WIDTH - 4));
        // Below the hovered character if it fits, otherwise above it.
        y = anchorY + height + 4 <= screenHeight ? anchorY : Math.max(4, anchorY - font.lineHeight - height - 2);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
    }

    public void scroll(double amount) {
        int maxScroll = Math.max(0, contentHeight - (height - PADDING * 2));
        scroll = Mth.clamp(scroll - amount * SCROLL_STEP, 0, maxScroll);
    }

    public void extract(GuiGraphicsExtractor graphics, Font font) {
        Overlays.panel(graphics, x, y, WIDTH, height);
        int top = y + PADDING;
        int bottom = y + height - PADDING;
        graphics.enableScissor(x + 1, top, x + WIDTH - 1, bottom);
        int rowY = top - (int) scroll;
        for (Row row : rows) {
            if (rowY + font.lineHeight >= top && rowY <= bottom) {
                if (row.divider()) {
                    graphics.fill(x + PADDING, rowY + font.lineHeight / 2, x + WIDTH - PADDING, rowY + font.lineHeight / 2 + 1, 0x60FFFFFF);
                } else {
                    graphics.text(font, row.text(), x + PADDING + row.offset(), rowY, 0xFFFFFFFF, false);
                }
            }
            rowY += font.lineHeight;
        }
        graphics.disableScissor();

        int viewport = height - PADDING * 2;
        if (contentHeight > viewport) {
            int barHeight = Math.max(8, viewport * viewport / contentHeight);
            int barY = top + (int) ((viewport - barHeight) * (scroll / (contentHeight - viewport)));
            graphics.fill(x + WIDTH - 3, barY, x + WIDTH - 1, barY + barHeight, 0xA0FFFFFF);
        }
    }
}
