package net.psunset.translatorpp.yomitan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One result row of a Yomitan lookup, reduced to what the popup draws.
 *
 * @param headwords   the spellings merged into this entry, each with its own tags (⭐, ichi, news1k)
 * @param matchLength how many characters of the query the entry matched (UTF-16 units)
 * @param inflections deinflection chains, e.g. [["-ます", "-た"]]; empty when uninflected
 * @param definitions senses in Yomitan's order
 */
public record TermEntry(List<Headword> headwords, int matchLength, List<List<String>> inflections,
                        List<Definition> definitions) {

    /** A Yomitan tag; {@code category} picks its colour (partOfSpeech, frequent, popular, name, ...). */
    public record Tag(String name, String category) {
    }

    public record Headword(String term, String reading, List<Tag> tags) {
    }

    public record Definition(String dictionary, List<Tag> tags, List<GlossaryRenderer.Line> lines) {
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
        List<Headword> headwords = new ArrayList<>();
        for (JsonElement element : array(entry, "headwords")) {
            JsonObject headword = element.getAsJsonObject();
            headwords.add(new Headword(string(headword, "term"), string(headword, "reading"), tags(headword)));
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
            definitions.add(new Definition(
                    string(definition, "dictionary"),
                    tags(definition),
                    GlossaryRenderer.render(array(definition, "entries"))));
        }

        int matchLength = entry.has("maxOriginalTextLength") ? entry.get("maxOriginalTextLength").getAsInt() : 0;
        return new TermEntry(headwords, matchLength, inflections, definitions);
    }

    private static List<Tag> tags(JsonObject owner) {
        List<Tag> tags = new ArrayList<>();
        for (JsonElement element : array(owner, "tags")) {
            JsonObject tag = element.getAsJsonObject();
            String name = string(tag, "name");
            String category = string(tag, "category");
            // JMdict numbers its senses with a "default" tag ("1", "2"); the popup numbers them itself.
            if (!name.isEmpty() && !(category.equals("default") && name.chars().allMatch(Character::isDigit))) {
                tags.add(new Tag(name, category));
            }
        }
        return tags;
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
