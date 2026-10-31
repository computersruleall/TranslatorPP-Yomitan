package net.psunset.translatorpp.core;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares chat text for a translation service: finds the message inside a chat
 * line, decides whether it's worth translating, and shields parts a service must
 * not touch. Service output is treated as untrusted text. The filtering follows
 * the DubTranslate server plugin's.
 */
public final class ChatText {
    private static final Pattern LEGACY_CODE = Pattern.compile("(?s)§.?");
    private static final Pattern BAD_CHARS = Pattern.compile("[\\p{Cc}\\u200b\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** URLs, [item]-style tags, :emoji:, @mentions and coordinates. */
    private static final Pattern PROTECTED = Pattern.compile(
            "https?://\\S+|www\\.\\S+|\\[[^\\]\\n]{1,40}\\]|:[A-Za-z0-9_]{2,32}:|@\\w{1,32}|-?\\d+[ ,]+-?\\d+[ ,]+-?\\d+");
    private static final Pattern TOKEN = Pattern.compile("\\{\\s*(\\d+)\\s*\\}");
    private static final Set<String> SKIP_WORDS = Set.of("gg", "ggs", "lol", "lmao", "lmfao", "rofl", "ok", "okay", "k", "kk",
            "ty", "thx", "np", "brb", "afk", "idk", "wtf", "xd");
    private static final int MAX_LENGTH = 600;
    private static final int MAX_MESSAGE_LENGTH = 300;
    private static final int MIN_LETTERS = 2;

    /** A message with its protected parts swapped for {0}-style tokens. */
    public record Prepared(String shielded, List<String> tokens) {

        /** Puts the protected parts back; any a service dropped go at the end. */
        public String restore(String translated) {
            boolean[] used = new boolean[tokens.size()];
            Matcher matcher = TOKEN.matcher(translated);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                int index = parseIndex(matcher.group(1));
                if (index >= 0 && index < tokens.size()) {
                    used[index] = true;
                    matcher.appendReplacement(out, Matcher.quoteReplacement(tokens.get(index)));
                } else {
                    matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(out);
            for (int i = 0; i < used.length; i++) {
                if (!used[i]) {
                    out.append(' ').append(tokens.get(i));
                }
            }
            return out.toString();
        }

        private static int parseIndex(String digits) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException tooLong) {
                return -1;
            }
        }
    }

    private ChatText() {
    }

    /**
     * Where the message starts in a chat line: after the first ": " of a server's
     * chat format, or after vanilla's "&lt;name&gt; ". A line with neither is all message.
     */
    public static int bodyStart(String line) {
        int colon = line.indexOf(": ");
        if (colon >= 0) {
            return colon + 2;
        }
        int bracket = line.startsWith("<") ? line.indexOf("> ") : -1;
        return bracket >= 0 ? bracket + 2 : 0;
    }

    /** Where the message ends: before any symbols fencing it, such as a server's clickable icons. */
    public static int bodyEnd(String line, int start) {
        int end = line.length();
        while (end > start) {
            int codePoint = line.codePointBefore(end);
            int type = Character.getType(codePoint);
            boolean symbol = type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL || type == Character.MODIFIER_SYMBOL;
            if (!symbol && !Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return end;
    }

    public static Prepared prepare(String body) {
        String clean = LEGACY_CODE.matcher(body).replaceAll("");
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PROTECTED.matcher(clean);
        StringBuilder shielded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(shielded, Matcher.quoteReplacement("{" + tokens.size() + "}"));
            tokens.add(matcher.group());
        }
        matcher.appendTail(shielded);
        return new Prepared(shielded.toString().strip(), tokens);
    }

    /** Whether translating this automatically would help at all. On demand, the player has already decided. */
    public static boolean worthTranslating(Prepared prepared) {
        String text = TOKEN.matcher(prepared.shielded()).replaceAll("").strip();
        if (text.length() > MAX_MESSAGE_LENGTH || text.codePoints().filter(Character::isLetter).count() < MIN_LETTERS) {
            return false;
        }
        return !SKIP_WORDS.contains(text.toLowerCase(Locale.ROOT));
    }

    /** Whether most of the text's letters are Latin, the case that could be romaji or plain English. */
    public static boolean isLatin(String text) {
        Map<UnicodeScript, Integer> counts = new EnumMap<>(UnicodeScript.class);
        text.codePoints().filter(Character::isLetter).forEach(c -> counts.merge(UnicodeScript.of(c), 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey() == UnicodeScript.LATIN).orElse(false);
    }

    public static String sanitize(String text) {
        String cleaned = LEGACY_CODE.matcher(text).replaceAll("");
        cleaned = BAD_CHARS.matcher(cleaned).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    public static boolean sameText(String left, String right) {
        return SPACES.matcher(left).replaceAll(" ").strip().equalsIgnoreCase(SPACES.matcher(right).replaceAll(" ").strip());
    }

    /** Whether two language tags name the same language, ignoring script and region (zh-Hans, zh-CN). */
    public static boolean sameLanguage(String left, String right) {
        return primary(left).equals(primary(right));
    }

    private static String primary(String tag) {
        int dash = tag.indexOf('-');
        return (dash < 0 ? tag : tag.substring(0, dash)).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a server address is in a comma-separated list. An entry matches its
     * own address and subdomains, whatever the port: "example.net" covers
     * "play.example.net:25565".
     */
    public static boolean serverListed(String list, String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String host = hostOf(address);
        for (String entry : list.split(",")) {
            String wanted = hostOf(entry);
            if (!wanted.isEmpty() && (host.equals(wanted) || host.endsWith("." + wanted))) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String address) {
        String host = address.strip().toLowerCase(Locale.ROOT);
        int port = host.lastIndexOf(':');
        return port >= 0 ? host.substring(0, port) : host;
    }
}
