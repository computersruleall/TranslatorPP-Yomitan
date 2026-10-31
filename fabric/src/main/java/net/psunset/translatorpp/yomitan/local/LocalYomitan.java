package net.psunset.translatorpp.yomitan.local;

import com.google.gson.JsonArray;
import net.fabricmc.loader.api.FabricLoader;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.yomitan.GlossaryRenderer;
import net.psunset.translatorpp.yomitan.LookupProblem;
import net.psunset.translatorpp.yomitan.TermEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The built-in engine: looks words up in the imported dictionaries, and is the
 * only way the manager screen touches them. Lookups and every change to the
 * library run on one worker, so a dictionary can never be unmapped mid-lookup.
 * Imports run on their own thread and only join the library when finished, so
 * lookups keep working during an import.
 */
public final class LocalYomitan {
    private static final LocalYomitan INSTANCE = new LocalYomitan();

    public static LocalYomitan get() {
        return INSTANCE;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "Yomitan-Lookup"));
    private final ExecutorService importer = Executors.newSingleThreadExecutor(r -> daemon(r, "Yomitan-Import"));
    private final Path root = FabricLoader.getInstance().getConfigDir().resolve("translatorpp");

    private DictionaryLibrary library;
    private final List<DictionaryFile> open = new ArrayList<>();
    private final List<String> openTitles = new ArrayList<>();

    private volatile List<DictionaryLibrary.Info> snapshot = List.of();
    private volatile String status = "";

    private LocalYomitan() {
        worker.execute(() -> {
            try {
                library = new DictionaryLibrary(root);
                reopen();
            } catch (IOException | RuntimeException e) {
                status = "Couldn't load dictionaries: " + e.getMessage();
                TranslatorPP.LOGGER.error("Couldn't load dictionaries", e);
            }
        });
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** The dictionaries in the player's order, for display. */
    public List<DictionaryLibrary.Info> dictionaries() {
        return snapshot;
    }

    public String status() {
        return status;
    }

    public Path importFolder() {
        return root.resolve("import");
    }

    public CompletableFuture<List<TermEntry>> termEntries(String text) {
        return CompletableFuture.supplyAsync(() -> {
            if (open.isEmpty()) {
                throw new LookupProblem(snapshot.isEmpty()
                        ? "No dictionaries imported yet. Press the Manage Yomitan Dictionaries key to add some."
                        : "All dictionaries are turned off. Press the Manage Yomitan Dictionaries key to enable one.");
            }
            return toEntries(TermFinder.find(text, open));
        }, worker);
    }

    private List<TermEntry> toEntries(List<TermFinder.Entry> found) {
        List<TermEntry> entries = new ArrayList<>(found.size());
        for (TermFinder.Entry entry : found) {
            DictionaryFile dictionary = open.get(entry.dictionary());
            String title = openTitles.get(entry.dictionary());
            List<TermEntry.Definition> definitions = new ArrayList<>();
            List<TermEntry.Tag> headwordTags = List.of();
            for (JsonArray row : entry.rows()) {
                JsonArray glossary = row.get(5).getAsJsonArray();
                definitions.add(new TermEntry.Definition(title, tags(dictionary, row.get(2).getAsString()), GlossaryRenderer.render(glossary)));
                if (headwordTags.isEmpty() && row.size() > 7) {
                    headwordTags = tags(dictionary, row.get(7).getAsString());
                }
            }
            entries.add(new TermEntry(List.of(new TermEntry.Headword(entry.term(), entry.reading(), headwordTags)),
                    entry.matchLength(), entry.chains(), definitions));
        }
        return entries;
    }

    private static List<TermEntry.Tag> tags(DictionaryFile dictionary, String names) {
        List<TermEntry.Tag> tags = new ArrayList<>();
        for (String name : names.split(" ")) {
            // JMdict numbers its senses with tags; the popup numbers them itself.
            if (!name.isEmpty() && !name.chars().allMatch(Character::isDigit)) {
                tags.add(new TermEntry.Tag(name, dictionary.tagCategory(name)));
            }
        }
        return tags;
    }

    /** Imports zips one at a time, skipping any whose title is already in the library. */
    public void importZips(List<Path> zips) {
        for (Path zip : zips) {
            importer.execute(() -> importOne(zip));
        }
    }

    public void importFromFolder() {
        worker.execute(() -> {
            try {
                List<Path> zips = library.zipsInImportFolder();
                if (zips.isEmpty()) {
                    status = "No .zip files in the import folder.";
                }
                importZips(zips);
            } catch (IOException | RuntimeException e) {
                status = "Couldn't read the import folder: " + e.getMessage();
            }
        });
    }

    private void importOne(Path zip) {
        String name = zip.getFileName().toString();
        try {
            DictionaryImporter.Header header = DictionaryImporter.readHeader(zip);
            boolean known = CompletableFuture.supplyAsync(() -> library.hasTitle(header.title()), worker).join();
            if (known) {
                status = header.title() + " is already imported.";
                return;
            }
            status = "Importing " + header.title() + "...";
            DictionaryLibrary.Info info = library.prepare(zip, header, progress ->
                    status = "Importing " + header.title() + "... " + Math.round(progress * 100) + "%");
            worker.submit(() -> {
                library.add(info);
                reopen();
                return null;
            }).get();
            status = "Imported " + header.title() + " (" + String.format("%,d", info.terms()) + " terms).";
        } catch (IOException e) {
            status = "Couldn't import " + name + ": " + e.getMessage();
        } catch (Exception e) {
            status = "Couldn't import " + name + ".";
            TranslatorPP.LOGGER.error("Dictionary import failed", e);
        }
    }

    public void setEnabled(String id, boolean enabled) {
        change(() -> library.setEnabled(id, enabled));
    }

    public void move(String id, int delta) {
        change(() -> library.move(id, delta));
    }

    public void remove(String id) {
        change(() -> {
            closeAll();
            library.remove(id);
        });
    }

    private interface Change {
        void apply() throws IOException;
    }

    private void change(Change change) {
        worker.execute(() -> {
            try {
                change.apply();
            } catch (IOException | RuntimeException e) {
                status = "Couldn't save that change: " + e.getMessage();
                TranslatorPP.LOGGER.error("Dictionary library change failed", e);
            }
            try {
                reopen();
            } catch (RuntimeException e) {
                TranslatorPP.LOGGER.error("Couldn't reopen dictionaries", e);
            }
        });
    }

    private void closeAll() {
        open.forEach(DictionaryFile::close);
        open.clear();
        openTitles.clear();
    }

    /** Maps the enabled dictionaries, in order. One damaged dictionary doesn't take down the others. */
    private void reopen() {
        closeAll();
        for (DictionaryLibrary.Info info : library.infos()) {
            if (info.enabled()) {
                try {
                    open.add(new DictionaryFile(library.folder(info.id())));
                    openTitles.add(info.title());
                } catch (IOException | RuntimeException e) {
                    status = "Couldn't open " + info.title() + ": " + e.getMessage();
                    TranslatorPP.LOGGER.error("Couldn't open dictionary {}", info.title(), e);
                }
            }
        }
        snapshot = library.infos();
    }
}
