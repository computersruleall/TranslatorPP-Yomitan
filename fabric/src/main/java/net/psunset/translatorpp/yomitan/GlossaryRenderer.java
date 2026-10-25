package net.psunset.translatorpp.yomitan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Flattens Yomitan glossary entries (plain strings or "structured-content",
 * an HTML-like tree) into indented lines of Minecraft text. Layout is reduced
 * to what a tooltip can show: lists, line breaks, badges, bold/italic and
 * greyed example sentences. Images and ruby annotations are dropped.
 */
public final class GlossaryRenderer {

    public record Line(int indent, Component text) {
    }

    private static final Set<String> BLOCK_TAGS = Set.of("div", "p", "details", "summary", "table", "tr", "thead", "tbody");
    private static final Set<String> SKIPPED_TAGS = Set.of("img", "rt", "rp");
    /** {@code data.content} values that are attribution or metadata rather than meaning. */
    private static final Set<String> SKIPPED_CONTENT = Set.of("attribution", "backlink");
    private static final Set<String> MUTED_CONTENT = Set.of("extra-info", "example-sentence", "example-sentence-a",
            "example-sentence-b", "notes", "info-gloss", "sense-note");

    private final List<Line> lines = new ArrayList<>();
    private final Deque<int[]> listCounters = new ArrayDeque<>(); // {ordered ? 1 : 0, next number}
    private MutableComponent current = null;
    private int currentIndent = 0;
    private int indent = 0;

    private GlossaryRenderer() {
    }

    public static List<Line> render(JsonArray glossaryEntries) {
        GlossaryRenderer renderer = new GlossaryRenderer();
        for (JsonElement entry : glossaryEntries) {
            renderer.glossaryEntry(entry);
        }
        renderer.newline();
        return renderer.lines;
    }

    private void glossaryEntry(JsonElement entry) {
        newline();
        if (entry.isJsonPrimitive()) {
            append("◦ " + entry.getAsString(), Style.EMPTY);
        } else if (entry.isJsonObject()) {
            JsonObject object = entry.getAsJsonObject();
            switch (TermEntry.string(object, "type")) {
                case "text" -> append("◦ " + TermEntry.string(object, "text"), Style.EMPTY);
                case "structured-content" -> node(object.get("content"), Style.EMPTY);
                default -> { /* images and unknown types have no text form */ }
            }
        } else if (entry.isJsonArray()) {
            // Deinflection entries ([uninflected, [rules]]) carry no gloss of their own.
            return;
        }
        newline();
    }

    private void node(JsonElement node, Style style) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonPrimitive()) {
            append(node.getAsString(), style);
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                node(child, style);
            }
            return;
        }

        JsonObject element = node.getAsJsonObject();
        String tag = TermEntry.string(element, "tag");
        String dataContent = element.has("data") && element.get("data").isJsonObject()
                ? TermEntry.string(element.getAsJsonObject("data"), "content")
                : "";
        if (SKIPPED_TAGS.contains(tag) || SKIPPED_CONTENT.contains(dataContent)) {
            return;
        }

        Style childStyle = applyCss(element, style);
        if (MUTED_CONTENT.contains(dataContent)) {
            childStyle = childStyle.withColor(ChatFormatting.GRAY);
        }
        JsonElement content = element.get("content");

        switch (tag) {
            case "br" -> newline();
            case "ul", "ol" -> {
                newline();
                listCounters.push(new int[]{tag.equals("ol") ? 1 : 0, 1});
                node(content, childStyle);
                listCounters.pop();
                newline();
            }
            case "li" -> {
                newline();
                append(listMarker(element), childStyle);
                indent++;
                node(content, childStyle);
                indent--;
                newline();
            }
            case "td", "th" -> {
                node(content, childStyle);
                append(" ", childStyle);
            }
            case "span" -> {
                if (isBadge(element)) {
                    // Part-of-speech and usage labels ("5-dan", "kana"): Yomitan draws them as pills.
                    append(flatText(content), childStyle.withColor(ChatFormatting.DARK_AQUA));
                    append(" ", style);
                } else {
                    node(content, childStyle);
                }
            }
            default -> {
                if (BLOCK_TAGS.contains(tag)) {
                    newline();
                    node(content, childStyle);
                    newline();
                } else {
                    node(content, childStyle);
                }
            }
        }
    }

    private String listMarker(JsonObject li) {
        String custom = cssString(li, "listStyleType");
        if (custom != null && custom.length() > 2 && custom.startsWith("\"") && custom.endsWith("\"")) {
            return custom.substring(1, custom.length() - 1) + " ";
        }
        int[] list = listCounters.peek();
        if (list != null && list[0] == 1) {
            return (list[1]++) + ". ";
        }
        if (custom != null && custom.equals("none")) {
            return "";
        }
        return "◦ ";
    }

    private static boolean isBadge(JsonObject span) {
        return span.has("title") && cssString(span, "backgroundColor") != null;
    }

    private static Style applyCss(JsonObject element, Style style) {
        if ("bold".equals(cssString(element, "fontWeight"))) {
            style = style.withBold(true);
        }
        if ("italic".equals(cssString(element, "fontStyle"))) {
            style = style.withItalic(true);
        }
        return style;
    }

    private static String cssString(JsonObject element, String property) {
        if (!element.has("style") || !element.get("style").isJsonObject()) {
            return null;
        }
        JsonElement value = element.getAsJsonObject("style").get(property);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String flatText(JsonElement node) {
        if (node == null || node.isJsonNull()) {
            return "";
        }
        if (node.isJsonPrimitive()) {
            return node.getAsString();
        }
        StringBuilder builder = new StringBuilder();
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                builder.append(flatText(child));
            }
        } else if (!SKIPPED_TAGS.contains(TermEntry.string(node.getAsJsonObject(), "tag"))) {
            builder.append(flatText(node.getAsJsonObject().get("content")));
        }
        return builder.toString();
    }

    private void append(String text, Style style) {
        if (text.isEmpty()) {
            return;
        }
        if (current == null) {
            if (text.isBlank()) {
                return;
            }
            current = Component.empty();
            currentIndent = indent;
        }
        current.append(Component.literal(text).withStyle(style));
    }

    private void newline() {
        if (current != null) {
            lines.add(new Line(currentIndent, current));
            current = null;
        }
    }
}
