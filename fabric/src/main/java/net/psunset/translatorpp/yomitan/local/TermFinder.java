package net.psunset.translatorpp.yomitan.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lookup itself, the way Yomitan's popup scans: try the text from the hovered
 * character at every length, undo conjugation on each, and keep whatever the
 * enabled dictionaries confirm. Longest match first, then the fewest conjugation
 * steps, then the player's dictionary order.
 */
final class TermFinder {
    /** A common reading such as か matches thousands of names; beyond this they aren't useful. */
    private static final int MAX_ROWS_PER_KEY = 300;
    private static final int MAX_ENTRIES = 32;

    /**
     * A headword with its senses from one dictionary.
     *
     * @param dictionary  index into the list passed to {@link #find}
     * @param matchLength characters of the text it matched, in UTF-16 units
     * @param rows        the matched rows, highest scoring first
     * @param chains      the shortest ways to get here by undoing conjugation
     */
    record Entry(int dictionary, String term, String reading, int matchLength, List<JsonArray> rows, List<List<String>> chains) {
    }

    private static final class Builder {
        final int dictionary;
        final String term;
        final String reading;
        final Map<Long, JsonArray> rows = new LinkedHashMap<>();
        final Set<List<String>> chains = new LinkedHashSet<>();
        int matchLength;
        int chainLength = Integer.MAX_VALUE;
        boolean exactTerm;
        long score = Long.MIN_VALUE;

        Builder(int dictionary, String term, String reading) {
            this.dictionary = dictionary;
            this.term = term;
            this.reading = reading;
        }
    }

    private TermFinder() {
    }

    static List<Entry> find(String text, List<DictionaryFile> dictionaries) {
        Map<String, Builder> builders = new LinkedHashMap<>();
        Map<String, Map<Long, JsonArray>> rowCache = new HashMap<>();
        Set<String> searched = new LinkedHashSet<>();

        for (int end = text.length(); end > 0; end = text.offsetByCodePoints(end, -1)) {
            String prefix = text.substring(0, end);
            for (String variant : variants(prefix)) {
                if (!searched.add(variant + '|' + end)) {
                    continue;
                }
                for (Deinflector.Candidate candidate : Deinflector.deinflect(variant)) {
                    for (int d = 0; d < dictionaries.size(); d++) {
                        match(candidate, end, d, dictionaries.get(d), builders, rowCache);
                    }
                }
            }
        }

        List<Builder> sorted = new ArrayList<>(builders.values());
        sorted.sort(Comparator.<Builder>comparingInt(b -> -b.matchLength)
                .thenComparingInt(b -> b.chainLength)
                .thenComparing(b -> !b.exactTerm)
                .thenComparingInt(b -> b.dictionary)
                .thenComparingLong(b -> -b.score));

        List<Entry> entries = new ArrayList<>();
        for (Builder builder : sorted.subList(0, Math.min(MAX_ENTRIES, sorted.size()))) {
            List<JsonArray> rows = new ArrayList<>(builder.rows.values());
            rows.sort(Comparator.comparingLong(row -> -row.get(4).getAsLong()));
            entries.add(new Entry(builder.dictionary, builder.term, builder.reading, builder.matchLength, rows,
                    builder.chains.stream().filter(chain -> !chain.isEmpty()).toList()));
        }
        return entries;
    }

    private static void match(Deinflector.Candidate candidate, int matchLength, int d, DictionaryFile dictionary,
                              Map<String, Builder> builders, Map<String, Map<Long, JsonArray>> rowCache) {
        Map<Long, JsonArray> rows = rowCache.computeIfAbsent(d + "|" + candidate.term(), key -> {
            Map<Long, JsonArray> parsed = new LinkedHashMap<>();
            for (long offset : dictionary.find(candidate.term(), MAX_ROWS_PER_KEY)) {
                parsed.put(offset, JsonParser.parseString(dictionary.row(offset)).getAsJsonArray());
            }
            return parsed;
        });
        for (Map.Entry<Long, JsonArray> found : rows.entrySet()) {
            JsonArray row = found.getValue();
            if (candidate.types() != 0
                    && (Deinflector.typesOf(row.get(3).getAsString(), row.get(2).getAsString()) & candidate.types()) == 0) {
                continue;
            }
            String term = row.get(0).getAsString();
            String reading = row.get(1).getAsString();
            Builder builder = builders.computeIfAbsent(d + "|" + term + "|" + reading, key -> new Builder(d, term, reading));
            builder.rows.put(found.getKey(), row);
            builder.score = Math.max(builder.score, row.get(4).getAsLong());
            int chainLength = candidate.chain().size();
            if (matchLength > builder.matchLength || (matchLength == builder.matchLength && chainLength < builder.chainLength)) {
                builder.matchLength = matchLength;
                builder.chainLength = chainLength;
                builder.chains.clear();
            }
            if (matchLength == builder.matchLength && chainLength == builder.chainLength) {
                builder.chains.add(candidate.chain());
                builder.exactTerm |= candidate.term().equals(term);
            }
        }
    }

    /** The spellings worth trying: as written, in the other kana script, and with half-width forms normalized. */
    static Set<String> variants(String text) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(text);
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        variants.add(normalized);
        variants.add(shiftKana(normalized, 0x30A1, 0x30F6, -0x60));
        variants.add(shiftKana(normalized, 0x3041, 0x3096, 0x60));
        return variants;
    }

    private static String shiftKana(String text, int first, int last, int shift) {
        StringBuilder shifted = new StringBuilder(text.length());
        text.codePoints().forEach(c -> shifted.appendCodePoint(c >= first && c <= last ? c + shift : c));
        return shifted.toString();
    }
}
