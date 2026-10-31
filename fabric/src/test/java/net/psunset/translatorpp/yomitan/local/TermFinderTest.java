package net.psunset.translatorpp.yomitan.local;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermFinderTest {
    private static final String BANK = """
            [["食べる","たべる","v1 vt","v1",100,["to eat"],1,"ichi"],
             ["食べる","たべる","v1","v1",90,["to live on"],1,"ichi"],
             ["読む","よむ","v5","v5",100,["to read"],2,""],
             ["勉強","べんきょう","n vs","",100,["study"],3,""],
             ["高い","たかい","adj-i","adj-i",100,["tall"],4,""],
             ["テスト","","n","",100,["test"],5,""]]""";

    @TempDir
    Path temp;

    private Path zip(String name, Map<String, String> entries) throws IOException {
        Path file = temp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private DictionaryFile imported(String title, String bank) throws IOException {
        Path zip = zip(title + ".zip", Map.of(
                "index.json", "{\"title\":\"" + title + "\",\"format\":3,\"revision\":\"1\"}",
                "term_bank_1.json", bank,
                "tag_bank_1.json", "[[\"n\",\"partOfSpeech\",0,\"noun\",0]]"));
        Path folder = Files.createDirectories(temp.resolve(title));
        DictionaryImporter.importTo(zip, folder, p -> {
        });
        return new DictionaryFile(folder);
    }

    @Test
    void findsConjugatedWordsAndKeepsSensesTogether() throws IOException {
        try (DictionaryFile dictionary = imported("a", BANK)) {
            TermFinder.Entry entry = TermFinder.find("食べました", List.of(dictionary)).getFirst();
            assertEquals("食べる", entry.term());
            assertEquals(5, entry.matchLength());
            assertEquals(List.of(List.of("-ます", "-た")), entry.chains());
            assertEquals(2, entry.rows().size());
            assertEquals(100, entry.rows().getFirst().get(4).getAsInt());

            assertEquals("読む", TermFinder.find("読んでいた", List.of(dictionary)).getFirst().term());
            assertEquals("高い", TermFinder.find("高くなかった", List.of(dictionary)).getFirst().term());
        }
    }

    @Test
    void sharesReadingsAndKanaScripts() throws IOException {
        try (DictionaryFile dictionary = imported("a", BANK)) {
            assertEquals("食べる", TermFinder.find("たべる", List.of(dictionary)).getFirst().term());
            assertEquals("食べる", TermFinder.find("タベル", List.of(dictionary)).getFirst().term());
            assertEquals("テスト", TermFinder.find("てすと", List.of(dictionary)).getFirst().term());
        }
    }

    @Test
    void wordTypeMustAgreeWithTheConjugation() throws IOException {
        try (DictionaryFile dictionary = imported("a", BANK)) {
            // 勉強した reaches the noun only through its する tag; テストした has no such tag.
            TermFinder.Entry study = TermFinder.find("勉強した", List.of(dictionary)).getFirst();
            assertEquals("勉強", study.term());
            assertEquals(4, study.matchLength());
            assertEquals(3, TermFinder.find("テストした", List.of(dictionary)).getFirst().matchLength());
        }
    }

    @Test
    void dictionaryOrderDecidesTies() throws IOException {
        String other = "[[\"テスト\",\"\",\"n\",\"\",999,[\"other\"],1,\"\"]]";
        try (DictionaryFile a = imported("a", BANK); DictionaryFile b = imported("b", other)) {
            assertEquals(0, TermFinder.find("テスト", List.of(a, b)).getFirst().dictionary());
            assertEquals("other", TermFinder.find("テスト", List.of(b, a)).getFirst().rows().getFirst().get(5).getAsJsonArray().get(0).getAsString());
        }
    }

    @Test
    void readsOlderFormatRows() throws IOException {
        try (DictionaryFile dictionary = imported("a", "[[\"犬\",\"いぬ\",\"n\",\"\",0,\"dog\",\"hound\"]]")) {
            assertEquals(2, TermFinder.find("犬", List.of(dictionary)).getFirst().rows().getFirst().get(5).getAsJsonArray().size());
        }
    }

    @Test
    void libraryKeepsOrderAndEnabledAcrossRestarts() throws IOException {
        Path root = temp.resolve("library");
        Path zipA = zip("x.zip", Map.of("index.json", "{\"title\":\"X\",\"format\":3}", "term_bank_1.json", BANK));
        Path zipB = zip("y.zip", Map.of("index.json", "{\"title\":\"Y\",\"format\":3}", "term_bank_1.json", BANK));
        DictionaryLibrary library = new DictionaryLibrary(root);
        DictionaryLibrary.Info x = library.prepare(zipA, DictionaryImporter.readHeader(zipA), p -> {
        });
        library.add(x);
        DictionaryLibrary.Info y = library.prepare(zipB, DictionaryImporter.readHeader(zipB), p -> {
        });
        library.add(y);
        library.move(y.id(), -1);
        library.setEnabled(x.id(), false);

        DictionaryLibrary reloaded = new DictionaryLibrary(root);
        assertEquals(List.of("Y", "X"), reloaded.infos().stream().map(DictionaryLibrary.Info::title).toList());
        assertFalse(reloaded.infos().get(1).enabled());
        assertEquals(6, reloaded.infos().getFirst().terms());

        reloaded.remove(x.id());
        assertFalse(Files.exists(reloaded.folder(x.id())));
        assertEquals(1, new DictionaryLibrary(root).infos().size());
    }

    @Test
    void rejectsWhatIsNotADictionary() throws IOException {
        Path notDictionary = zip("n.zip", Map.of("readme.txt", "hi"));
        assertThrows(IOException.class, () -> DictionaryImporter.readHeader(notDictionary));
        Path noTerms = zip("k.zip", Map.of("index.json", "{\"title\":\"K\",\"format\":3}", "kanji_bank_1.json", "[]"));
        assertThrows(IOException.class, () -> DictionaryImporter.importTo(noTerms, Files.createDirectories(temp.resolve("k")), p -> {
        }));
        Path malformed = zip("m.zip", Map.of("index.json", "{\"title\":\"M\",\"format\":3}", "term_bank_1.json", "[[\"a\"]]"));
        assertThrows(IOException.class, () -> DictionaryImporter.importTo(malformed, Files.createDirectories(temp.resolve("m")), p -> {
        }));
    }

    /** Runs against real dictionaries when YOMITAN_TEST_ZIPS lists them, separated by the path separator. */
    @Test
    void realDictionaries() throws IOException {
        String zips = System.getenv("YOMITAN_TEST_ZIPS");
        Assumptions.assumeTrue(zips != null && !zips.isBlank());
        List<DictionaryFile> dictionaries = new ArrayList<>();
        try {
            for (String zipPath : zips.split(File.pathSeparator)) {
                Path folder = Files.createTempDirectory(temp, "real");
                long start = System.currentTimeMillis();
                long rows = DictionaryImporter.importTo(Path.of(zipPath), folder, p -> {
                });
                long size;
                try (var files = Files.list(folder)) {
                    size = files.mapToLong(f -> f.toFile().length()).sum();
                }
                System.out.printf("imported %s: %d rows, %d MB, %d ms%n", Path.of(zipPath).getFileName(), rows, size / 1_000_000, System.currentTimeMillis() - start);
                dictionaries.add(new DictionaryFile(folder));
            }
            String[][] expected = {
                    {"食べました", "食べる"}, {"分かりませんでした", "分かる"}, {"読んでいた", "読む"}, {"行った", "行く"},
                    {"行きたくない", "行く"}, {"書かれた", "書く"}, {"来ました", "来る"}, {"きた", "来る"}, {"来なかった", "来る"},
                    {"した", "為る"}, {"しました", "為る"}, {"勉強した", "勉強"}, {"勉強している", "勉強"}, {"高かった", "高い"},
                    {"高くない", "高い"}, {"泳げる", "泳ぐ"}, {"泳げない", "泳ぐ"}, {"走ろう", "走る"}, {"飲ませる", "飲む"},
                    {"見られる", "見る"}, {"食べちゃった", "食べる"}, {"書いておいた", "書く"}, {"死んだ", "死ぬ"}, {"遊んで", "遊ぶ"},
                    {"話して", "話す"}, {"待って", "待つ"}, {"買った", "買う"}, {"帰ったら", "帰る"}, {"忘れないで", "忘れる"},
                    {"おはよう", "お早う"}, {"テスト", "テスト"}, {"創造された", "創造"}, {"看板", "看板"}, {"信じた", "信じる"},
                    {"飲みすぎた", "飲みすぎる"}, {"行けば", "行く"}, {"美味しそう", "美味しい"}
            };
            int wrong = 0;
            for (String[] test : expected) {
                long start = System.nanoTime();
                List<TermFinder.Entry> entries = TermFinder.find(test[0], dictionaries);
                long micros = (System.nanoTime() - start) / 1000;
                List<String> terms = entries.stream().limit(6).map(TermFinder.Entry::term).toList();
                TermFinder.Entry first = entries.isEmpty() ? null : entries.getFirst();
                boolean ok = entries.stream().limit(6).anyMatch(e -> e.term().equals(test[1]));
                wrong += ok ? 0 : 1;
                System.out.printf("%s %s -> %s match=%d chains=%s (%d entries, %d us)%n", ok ? "ok  " : "MISS", test[0], terms,
                        first == null ? 0 : first.matchLength(), first == null ? "" : first.chains(), entries.size(), micros);
            }
            for (String probe : new String[]{"かかかかかかかかかかかかかかかかかかかか", "ひらがなばかりのながいぶんしょうです", "日本語の文章を読んでいました"}) {
                long start = System.nanoTime();
                int found = TermFinder.find(probe, dictionaries).size();
                System.out.printf("probe %s: %d entries, %d ms%n", probe, found, (System.nanoTime() - start) / 1_000_000);
            }
            System.out.printf("%d of %d missed%n", wrong, expected.length);
            assertTrue(wrong <= 3, wrong + " of the expected words were not found");
        } finally {
            dictionaries.forEach(DictionaryFile::close);
        }
    }
}
