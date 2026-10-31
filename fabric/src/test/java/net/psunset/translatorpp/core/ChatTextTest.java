package net.psunset.translatorpp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTextTest {

    private static String body(String line) {
        int start = ChatText.bodyStart(line);
        return line.substring(start, ChatText.bodyEnd(line, start));
    }

    @Test
    void findsTheMessageInsideAChatLine() {
        assertEquals("おはよう", body("[He₂] BrownU: おはよう"));
        assertEquals("hello there", body("<Steve> hello there"));
        assertEquals("サーバーが再起動します", body("サーバーが再起動します"));
    }

    @Test
    void keepsTrailingIconsOutOfTheMessage() {
        assertEquals("I can smell client mods.", body("[He₂] BrownU: I can smell client mods. ⇄"));
        assertEquals("やった", body("Bob: やった ✕"));
    }

    @Test
    void protectedPartsSurviveTranslation() {
        ChatText.Prepared prepared = ChatText.prepare("見て https://example.com と [Diamond Sword] と @Bob 10 64 -20");
        assertEquals(4, prepared.tokens().size());
        assertFalse(prepared.shielded().contains("example.com"));
        String restored = prepared.restore("Look at {0} and {1} and {2} {3}");
        assertEquals("Look at https://example.com and [Diamond Sword] and @Bob 10 64 -20", restored);
    }

    @Test
    void tokensAServiceDroppedGoAtTheEnd() {
        assertEquals("Look https://example.com", ChatText.prepare("見て https://example.com").restore("Look"));
    }

    @Test
    void skipsWhatIsNotWorthTranslating() {
        assertFalse(ChatText.worthTranslating(ChatText.prepare("gg")));
        assertFalse(ChatText.worthTranslating(ChatText.prepare("LOL")));
        assertFalse(ChatText.worthTranslating(ChatText.prepare("12345")));
        assertFalse(ChatText.worthTranslating(ChatText.prepare("https://example.com")));
        assertFalse(ChatText.worthTranslating(ChatText.prepare("あ".repeat(301))));
        assertTrue(ChatText.worthTranslating(ChatText.prepare("おはよう")));
    }

    @Test
    void recognisesRomajiCandidates() {
        assertTrue(ChatText.isLatin("ohayou gozaimasu"));
        assertFalse(ChatText.isLatin("おはよう ok"));
        assertFalse(ChatText.isLatin("1234"));
    }

    @Test
    void cleansServiceOutput() {
        assertEquals("hello world", ChatText.sanitize("§chello‮  \n world\u0007"));
    }

    @Test
    void comparesLanguagesIgnoringScriptAndRegion() {
        assertTrue(ChatText.sameLanguage("zh-Hans", "zh-CN"));
        assertTrue(ChatText.sameLanguage("EN", "en-US"));
        assertFalse(ChatText.sameLanguage("ja", "en"));
    }

    @Test
    void matchesServersBySubdomainWithAnyPort() {
        String list = "example.net, jp.other.org:25566";
        assertTrue(ChatText.serverListed(list, "example.net"));
        assertTrue(ChatText.serverListed(list, "PLAY.example.net:25565"));
        assertTrue(ChatText.serverListed(list, "jp.other.org"));
        assertFalse(ChatText.serverListed(list, "other.org"));
        assertFalse(ChatText.serverListed(list, "notexample.net"));
        assertFalse(ChatText.serverListed("", "example.net"));
        assertFalse(ChatText.serverListed(list, null));
    }
}
