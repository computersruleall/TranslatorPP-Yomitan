package net.psunset.translatorpp.yomitan.local;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/**
 * The imported dictionaries and the player's choices about them: their order and
 * which are on. Each one lives in its own folder named by a generated id, never
 * by anything from the zip. Not thread safe: {@link LocalYomitan} serializes use.
 */
final class DictionaryLibrary {
    /** One imported dictionary, as saved in library.json and shown in the manager. */
    record Info(String id, String title, String revision, long terms, long bytes, boolean enabled) {
        Info withEnabled(boolean value) {
            return new Info(id, title, revision, terms, bytes, value);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path root;
    private final Path dictionaries;
    private final Path importFolder;
    private final Path libraryFile;
    private final List<Info> infos = new ArrayList<>();

    DictionaryLibrary(Path root) throws IOException {
        this.root = root;
        this.dictionaries = root.resolve("dictionaries");
        this.importFolder = root.resolve("import");
        this.libraryFile = root.resolve("library.json");
        Files.createDirectories(dictionaries);
        Files.createDirectories(importFolder);
        load();
        removeLeftovers();
    }

    private void load() throws IOException {
        if (!Files.exists(libraryFile)) {
            return;
        }
        try {
            List<Info> saved = GSON.fromJson(Files.readString(libraryFile, StandardCharsets.UTF_8), new TypeToken<List<Info>>() {
            }.getType());
            if (saved != null) {
                for (Info info : saved) {
                    if (info != null && info.id() != null && Files.isDirectory(folder(info.id()))) {
                        infos.add(info);
                    }
                }
            }
        } catch (JsonParseException e) {
            throw new IOException("library.json is damaged", e);
        }
    }

    /** An import interrupted by a crash leaves a folder the library doesn't list. */
    private void removeLeftovers() throws IOException {
        try (Stream<Path> folders = Files.list(dictionaries)) {
            for (Path folder : folders.toList()) {
                String name = folder.getFileName().toString();
                if (infos.stream().noneMatch(info -> info.id().equals(name))) {
                    deleteRecursively(folder);
                }
            }
        }
    }

    private void save() throws IOException {
        Path temporary = root.resolve("library.json.tmp");
        Files.writeString(temporary, GSON.toJson(infos), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, libraryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, libraryFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    List<Info> infos() {
        return List.copyOf(infos);
    }

    Path folder(String id) {
        return dictionaries.resolve(id);
    }

    Path importFolder() {
        return importFolder;
    }

    /** The zips waiting in the import folder, whether or not they're already imported. */
    List<Path> zipsInImportFolder() throws IOException {
        try (Stream<Path> files = Files.list(importFolder)) {
            return files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        }
    }

    boolean hasTitle(String title) {
        return infos.stream().anyMatch(info -> info.title().equals(title));
    }

    /**
     * Imports into a fresh folder, without touching the library, so a failure
     * leaves nothing behind and lookups can carry on meanwhile.
     */
    Info prepare(Path zip, DictionaryImporter.Header header, DoubleConsumer progress) throws IOException {
        String id = UUID.randomUUID().toString();
        Path folder = folder(id);
        Files.createDirectories(folder);
        try {
            long terms = DictionaryImporter.importTo(zip, folder, progress);
            return new Info(id, header.title(), header.revision(), terms, sizeOf(folder), true);
        } catch (IOException | RuntimeException e) {
            deleteRecursively(folder);
            throw e;
        }
    }

    /** New dictionaries go last, so they never reorder what the player already set up. */
    void add(Info info) throws IOException {
        infos.add(info);
        save();
    }

    void setEnabled(String id, boolean enabled) throws IOException {
        for (int i = 0; i < infos.size(); i++) {
            if (infos.get(i).id().equals(id)) {
                infos.set(i, infos.get(i).withEnabled(enabled));
            }
        }
        save();
    }

    void move(String id, int delta) throws IOException {
        for (int i = 0; i < infos.size(); i++) {
            if (infos.get(i).id().equals(id)) {
                int target = Math.max(0, Math.min(infos.size() - 1, i + delta));
                infos.add(target, infos.remove(i));
                save();
                return;
            }
        }
    }

    /** Caller must have closed the dictionary first: Windows won't delete a mapped file. */
    void remove(String id) throws IOException {
        if (infos.removeIf(info -> info.id().equals(id))) {
            save();
            deleteRecursively(folder(id));
        }
    }

    private static long sizeOf(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            long total = 0;
            for (Path file : files.toList()) {
                total += Files.size(file);
            }
            return total;
        }
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
