package net.psunset.translatorpp.yomitan.local;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Undoes Japanese conjugation: from an inflected word, every dictionary form it
 * could have come from, with the steps that lead there. Each rule swaps a suffix
 * and moves between word types, so forms chain (食べていました: past, polite,
 * progressive, te-form). A candidate is only real if the dictionary agrees with
 * its word type, so generous rules are harmless.
 */
public final class Deinflector {
    public static final int V1 = 1;
    public static final int V5 = 1 << 1;
    public static final int VS = 1 << 2;
    public static final int VK = 1 << 3;
    public static final int VZ = 1 << 4;
    public static final int ADJ_I = 1 << 5;
    /** A noun that takes する, such as 勉強. Dictionaries mark these in the part-of-speech tags. */
    public static final int NOUN_VS = 1 << 6;
    private static final int TE = 1 << 7;
    private static final int TA = 1 << 8;
    /** The stem before ます: 食べ, 読み. */
    private static final int STEM = 1 << 9;
    /** The stem before ない: 食べ, 読ま. */
    private static final int NEGATIVE_STEM = 1 << 10;

    /** Bounds the search on long inputs; real words need far fewer steps. */
    private static final int MAX_CANDIDATES = 400;

    /**
     * A possible dictionary form.
     *
     * @param types word types the entry must have; 0 means the text as written, which matches anything
     * @param chain the conjugation steps, starting from the dictionary form
     */
    public record Candidate(String term, int types, List<String> chain) {
    }

    private record Rule(String from, String to, int fromTypes, int toTypes, List<String> names) {
    }

    /** The other forms of each godan ending: i-stem, a-stem, e-stem, o-stem, te-form, past. */
    private static final Map<String, String[]> GODAN = Map.of(
            "く", new String[]{"き", "か", "け", "こ", "いて", "いた"},
            "ぐ", new String[]{"ぎ", "が", "げ", "ご", "いで", "いだ"},
            "す", new String[]{"し", "さ", "せ", "そ", "して", "した"},
            "つ", new String[]{"ち", "た", "て", "と", "って", "った"},
            "ぬ", new String[]{"に", "な", "ね", "の", "んで", "んだ"},
            "ぶ", new String[]{"び", "ば", "べ", "ぼ", "んで", "んだ"},
            "む", new String[]{"み", "ま", "め", "も", "んで", "んだ"},
            "る", new String[]{"り", "ら", "れ", "ろ", "って", "った"},
            "う", new String[]{"い", "わ", "え", "お", "って", "った"});
    private static final int I_STEM = 0;
    private static final int A_STEM = 1;
    private static final int E_STEM = 2;
    private static final int O_STEM = 3;
    private static final int TE_FORM = 4;
    private static final int PAST = 5;

    private static final List<Rule> RULES = buildRules();

    private Deinflector() {
    }

    public static List<Candidate> deinflect(String text) {
        List<Candidate> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<Candidate> queue = new ArrayDeque<>();
        Candidate start = new Candidate(text, 0, List.of());
        queue.add(start);
        seen.add(text + '|' + 0);
        while (!queue.isEmpty() && results.size() < MAX_CANDIDATES) {
            Candidate current = queue.poll();
            results.add(current);
            for (Rule rule : RULES) {
                if ((current.types() != 0 && (current.types() & rule.fromTypes()) == 0) || !current.term().endsWith(rule.from())) {
                    continue;
                }
                String term = current.term().substring(0, current.term().length() - rule.from().length()) + rule.to();
                if (term.isEmpty() || !seen.add(term + '|' + rule.toTypes())) {
                    continue;
                }
                List<String> chain = new ArrayList<>(rule.names());
                chain.addAll(current.chain());
                queue.add(new Candidate(term, rule.toTypes(), chain));
            }
        }
        return results;
    }

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // The two stems every other form builds on. They have no name of their own.
        conjugation(rules, List.of(), STEM, "", I_STEM, "", "し", "き");
        conjugation(rules, List.of(), NEGATIVE_STEM, "", A_STEM, "", "し", "こ");

        conjugation(rules, List.of("-た"), TA, "た", PAST, "", "した", "きた");
        conjugation(rules, List.of("-て"), TE, "て", TE_FORM, "", "して", "きて");
        add(rules, "いった", "いく", TA, V5, "-た");
        add(rules, "行った", "行く", TA, V5, "-た");
        add(rules, "いって", "いく", TE, V5, "-て");
        add(rules, "行って", "行く", TE, V5, "-て");

        conjugation(rules, List.of("passive"), V1, "られる", A_STEM, "れる", "される", "こられる");
        conjugation(rules, List.of("potential"), V1, "れる", E_STEM, "る", "できる", "これる");
        conjugation(rules, List.of("causative"), V1, "させる", A_STEM, "せる", "させる", "こさせる");
        conjugation(rules, List.of("causative"), V5, "さす", A_STEM, "す", "さす", "こさす");
        conjugation(rules, List.of("volitional"), 0, "よう", O_STEM, "う", "しよう", "こよう");
        conjugation(rules, List.of("-ば"), 0, "れば", E_STEM, "ば", "すれば", "くれば");
        conjugation(rules, List.of("imperative"), 0, "ろ", E_STEM, "", "しろ", "こい");
        add(rules, "よ", "る", 0, V1, "imperative");
        add(rules, "せよ", "する", 0, VS, "imperative");

        // Past-based forms.
        add(rules, "たら", "た", 0, TA, "-たら");
        add(rules, "だら", "だ", 0, TA, "-たら");
        add(rules, "たり", "た", 0, TA, "-たり");
        add(rules, "だり", "だ", 0, TA, "-たり");

        // Built on the te-form; the result keeps its て/で so the te-form rules finish the job.
        for (String[] te : new String[][]{{"て", "ち"}, {"で", "じ"}}) {
            String t = te[0];
            add(rules, t + "いる", t, V1, TE, "-ている");
            add(rules, t + "る", t, V1, TE, "-ている");
            add(rules, t + "いく", t, V5, TE, "-ていく");
            add(rules, t + "く", t, V5, TE, "-ていく");
            add(rules, t + "くる", t, VK, TE, "-てくる");
            add(rules, t + "おく", t, V5, TE, "-ておく");
            add(rules, t + "ある", t, V5, TE, "-てある");
            add(rules, t + "みる", t, V1, TE, "-てみる");
            add(rules, t + "しまう", t, V5, TE, "-てしまう");
            add(rules, t + "ください", t, 0, TE, "-てください");
            add(rules, t + "ほしい", t, ADJ_I, TE, "-てほしい");
            add(rules, te[1] + "ゃう", t, V5, TE, "-てしまう");
            add(rules, te[1] + "まう", t, V5, TE, "-てしまう");
            add(rules, te[1] + "ゃ", t, 0, TE, "-ては");
        }
        add(rules, "とく", "て", V5, TE, "-ておく");
        add(rules, "どく", "で", V5, TE, "-ておく");

        // Built on the ます stem.
        add(rules, "ます", "", 0, STEM, "-ます");
        add(rules, "ました", "", 0, STEM, "-ます", "-た");
        add(rules, "ましたら", "", 0, STEM, "-ます", "-たら");
        add(rules, "ません", "", 0, STEM, "-ます", "negative");
        add(rules, "ませんでした", "", 0, STEM, "-ます", "negative", "-た");
        add(rules, "ましょう", "", 0, STEM, "-ます", "volitional");
        add(rules, "まして", "", TE, STEM, "-ます", "-て");
        add(rules, "ませ", "", 0, STEM, "-ます", "imperative");
        add(rules, "たい", "", ADJ_I, STEM, "-たい");
        add(rules, "たがる", "", V5, STEM, "-たがる");
        add(rules, "ながら", "", 0, STEM, "-ながら");
        add(rules, "なさい", "", 0, STEM, "-なさい");
        add(rules, "そう", "", 0, STEM, "-そう");
        add(rules, "すぎる", "", V1, STEM, "-すぎる");
        add(rules, "過ぎる", "", V1, STEM, "-すぎる");
        add(rules, "やすい", "", ADJ_I, STEM, "-やすい");
        add(rules, "にくい", "", ADJ_I, STEM, "-にくい");
        add(rules, "づらい", "", ADJ_I, STEM, "-づらい");
        add(rules, "方", "", 0, STEM, "-方");

        // Built on the negative stem. ない itself conjugates like an i-adjective.
        add(rules, "ない", "", ADJ_I, NEGATIVE_STEM, "negative");
        add(rules, "ないで", "", 0, NEGATIVE_STEM, "negative", "-て");
        add(rules, "ず", "", 0, NEGATIVE_STEM, "negative");
        add(rules, "ずに", "", 0, NEGATIVE_STEM, "negative");
        add(rules, "ぬ", "", 0, NEGATIVE_STEM, "negative");
        add(rules, "ん", "", 0, NEGATIVE_STEM, "negative");
        add(rules, "なきゃ", "", 0, NEGATIVE_STEM, "negative", "-ば");
        add(rules, "なけりゃ", "", 0, NEGATIVE_STEM, "negative", "-ば");

        // i-adjectives.
        add(rules, "かった", "い", TA, ADJ_I, "-た");
        add(rules, "くて", "い", TE, ADJ_I, "-て");
        add(rules, "く", "い", 0, ADJ_I, "adverbial");
        add(rules, "ければ", "い", 0, ADJ_I, "-ば");
        add(rules, "くない", "い", ADJ_I, ADJ_I, "negative");
        add(rules, "さ", "い", 0, ADJ_I, "noun");
        add(rules, "そう", "い", 0, ADJ_I, "-そう");
        add(rules, "すぎる", "い", V1, ADJ_I, "-すぎる");
        add(rules, "過ぎる", "い", V1, ADJ_I, "-すぎる");
        add(rules, "かろう", "い", 0, ADJ_I, "volitional");
        add(rules, "き", "い", 0, ADJ_I, "attributive");

        // 勉強する → 勉強: the noun is what the dictionary lists.
        add(rules, "する", "", VS, NOUN_VS, "-する");
        add(rules, "為る", "", VS, NOUN_VS, "-する");
        return rules;
    }

    /**
     * One form across every verb class.
     *
     * @param ichidan  ending added to an ichidan stem
     * @param column   which godan form the ending attaches to
     * @param godan    ending added after that godan form
     * @param suru     the whole form of する
     * @param kuru     the whole form of くる, spelled in kana
     */
    private static void conjugation(List<Rule> rules, List<String> names, int fromTypes, String ichidan, int column,
                                    String godan, String suru, String kuru) {
        rules.add(new Rule(ichidan, "る", fromTypes, V1, names));
        for (Map.Entry<String, String[]> ending : GODAN.entrySet()) {
            rules.add(new Rule(ending.getValue()[column] + godan, ending.getKey(), fromTypes, V5, names));
        }
        rules.add(new Rule(suru, "する", fromTypes, VS, names));
        // ずる verbs (信ずる) conjugate like する with じ/ぜ; only the し/さ/せ-initial forms carry over.
        if (suru.startsWith("し")) {
            rules.add(new Rule("じ" + suru.substring(1), "ずる", fromTypes, VZ, names));
        } else if (suru.startsWith("さ")) {
            rules.add(new Rule("ぜら" + suru.substring(1), "ずる", fromTypes, VZ, names));
        } else if (suru.startsWith("す")) {
            rules.add(new Rule("ず" + suru.substring(1), "ずる", fromTypes, VZ, names));
        }
        rules.add(new Rule(kuru, "くる", fromTypes, VK, names));
        // くる's kanji spelling reads differently, but the written ending after 来 is the same.
        rules.add(new Rule("来" + kuru.substring(1), "来る", fromTypes, VK, names));
    }

    private static void add(List<Rule> rules, String from, String to, int fromTypes, int toTypes, String... names) {
        rules.add(new Rule(from, to, fromTypes, toTypes, List.of(names)));
    }

    /** The word-type bits a dictionary row satisfies, from its rule and part-of-speech fields. */
    public static int typesOf(String rules, String definitionTags) {
        int types = 0;
        for (String rule : rules.split(" ")) {
            types |= switch (rule) {
                case "v1", "v1d", "v1p" -> V1;
                case "v5", "v5d", "v5m", "v5s" -> V5;
                case "vs" -> VS;
                case "vk" -> VK;
                case "vz" -> VZ;
                case "adj-i" -> ADJ_I;
                default -> 0;
            };
        }
        for (String tag : definitionTags.split(" ")) {
            if (tag.equals("vs") || tag.equals("vs-i") || tag.equals("vs-s")) {
                types |= NOUN_VS;
            }
        }
        return types;
    }
}
