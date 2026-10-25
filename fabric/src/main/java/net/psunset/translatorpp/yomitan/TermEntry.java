package net.psunset.translatorpp.yomitan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One result row of a Yomitan lookup, reduced to what the popup draws.
 *
 * @param headwords   "term【reading】" for each headword merged into this entry
 * @param matchLength how many characters of the query the entry matched (UTF-16 units)
 * @param inflections deinflection chains, e.g. [["past", "polite"]]; empty when uninflected
 * @param definitions glosses in Yomitan's order, grouped per dictionary by the popup
 */
public record TermEntry(List<String> headwords, int matchLength, List<List<String>> inflections,
                        List<Definition> definitions) {

    public record Definition(String dictionary, List<String> tags, List<GlossaryRenderer.Line> lines) {
    }

    /**
     * Parses a {@code /termEntries} response. The payload is Yomitan's internal
     * format, so every field is read defensively.
     */
    static List<TermEntry> parseResponse(JsonElement response) {
        JsonObject root = response.isJsonArray()
                ? response.getAsJsonArray().get(0).getAsJsonObject()
                : response.getAsJsonObject();
        List<TermEntry> entries = new ArrayList<>();
        for (JsonElement element : array(root, "dictionaryEntries")) {
            entries.add(parseEntry(element.getAsJsonObject()));
        }
        return entries;
    }

    private static TermEntry parseEntry(JsonObject entry) {
        Set<String> headwords = new LinkedHashSet<>();
        for (JsonElement element : array(entry, "headwords")) {
            JsonObject headword = element.getAsJsonObject();
            String term = string(headword, "term");
            String reading = string(headword, "reading");
            headwords.add(reading.isEmpty() || reading.equals(term) ? term : term + "【" + reading + "】");
        }

        List<List<String>> inflections = new ArrayList<>();
        for (JsonElement element : array(entry, "inflectionRuleChainCandidates")) {
            List<String> chain = new ArrayList<>();
            for (JsonElement rule : array(element.getAsJsonObject(), "inflectionRules")) {
                // Older Yomitan sends plain strings, newer sends {name, description}.
                chain.add(rule.isJsonObject() ? string(rule.getAsJsonObject(), "name") : rule.getAsString());
            }
            if (!chain.isEmpty() && !inflections.contains(chain)) {
                inflections.add(chain);
            }
        }

        List<Definition> definitions = new ArrayList<>();
        for (JsonElement element : array(entry, "definitions")) {
            JsonObject definition = element.getAsJsonObject();
            List<String> tags = new ArrayList<>();
            for (JsonElement tag : array(definition, "tags")) {
                String name = string(tag.getAsJsonObject(), "name");
                if (!name.isEmpty()) {
                    tags.add(name);
                }
            }
            definitions.add(new Definition(
                    string(definition, "dictionary"),
                    tags,
                    GlossaryRenderer.render(array(definition, "entries"))));
        }

        int matchLength = entry.has("maxOriginalTextLength") ? entry.get("maxOriginalTextLength").getAsInt() : 0;
        return new TermEntry(List.copyOf(headwords), matchLength, inflections, definitions);
    }

    static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
