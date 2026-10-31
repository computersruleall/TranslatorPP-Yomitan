package net.psunset.translatorpp.yomitan.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts a Yomitan dictionary zip into the files {@link DictionaryFile} reads:
 * every term row in a data file, plus an index sorted by headword and reading.
 * The zip is only ever read entry by entry; nothing in it is extracted to a path
 * it names, so a hostile file can't write outside the target directory.
 */
final class DictionaryImporter {
    static final int INDEX_MAGIC = 0x59544D31; // "YTM1"
    static final String DATA_FILE = "terms.dat";
    static final String INDEX_FILE = "terms.idx";
    static final String TAGS_FILE = "tags.json";

    /** Header of a dictionary zip. */
    record Header(String title, String revision) {
    }

    private record Key(byte[] bytes, long dataOffset) {
    }

    private DictionaryImporter() {
    }

    static Header readHeader(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry("index.json");
            if (entry == null) {
                throw new IOException("not a Yomitan dictionary (no index.json)");
            }
            try (InputStream in = file.getInputStream(entry)) {
                JsonObject index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                int format = index.has("format") ? index.get("format").getAsInt() : index.has("version") ? index.get("version").getAsInt() : -1;
                if (format < 1 || format > 3) {
                    throw new IOException("unsupported dictionary format " + format);
                }
                if (!index.has("title")) {
                    throw new IOException("dictionary has no title");
                }
                return new Header(index.get("title").getAsString(), index.has("revision") ? index.get("revision").getAsString() : "");
            } catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
                throw new IOException("index.json is malformed", e);
            }
        }
    }

    /**
     * Writes the dictionary into {@code directory}, which must already exist.
     *
     * @return the number of term rows imported
     */
    static long importTo(Path zip, Path directory, DoubleConsumer progress) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            List<ZipEntry> termBanks = new ArrayList<>();
            List<ZipEntry> tagBanks = new ArrayList<>();
            file.stream().forEach(entry -> {
                if (entry.getName().matches("term_bank_\\d+\\.json")) {
                    termBanks.add(entry);
                } else if (entry.getName().matches("tag_bank_\\d+\\.json")) {
                    tagBanks.add(entry);
                }
            });
            if (termBanks.isEmpty()) {
                throw new IOException("the dictionary has no terms (kanji and frequency dictionaries aren't supported yet)");
            }

            writeTags(file, tagBanks, directory.resolve(TAGS_FILE));

            List<Key> keys = new ArrayList<>();
            long rows = 0;
            int done = 0;
            try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(directory.resolve(DATA_FILE)), 1 << 16))) {
                for (ZipEntry bank : termBanks) {
                    rows += writeBank(file, bank, data, keys);
                    progress.accept(0.9 * ++done / termBanks.size());
                }
            }
            writeIndex(keys, directory.resolve(INDEX_FILE));
            progress.accept(1);
            return rows;
        } catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            throw new IOException("the dictionary contains malformed data", e);
        }
    }

    private static void writeTags(ZipFile file, List<ZipEntry> banks, Path target) throws IOException {
        JsonArray all = new JsonArray();
        for (ZipEntry bank : banks) {
            try (InputStream in = file.getInputStream(bank)) {
                all.addAll(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray());
            }
        }
        Files.writeString(target, all.toString(), StandardCharsets.UTF_8);
    }

    private static long writeBank(ZipFile file, ZipEntry bank, DataOutputStream data, List<Key> keys) throws IOException {
        long rows = 0;
        try (InputStream in = file.getInputStream(bank);
             JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonArray row = normalize(JsonParser.parseReader(reader).getAsJsonArray());
                String term = row.get(0).getAsString();
                String reading = row.get(1).getAsString();
                byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
                // size() saturates at Integer.MAX_VALUE, which is where this format stops being able to count.
                if (data.size() == Integer.MAX_VALUE) {
                    throw new IOException("dictionary is too large (over 2 GB of term data)");
                }
                long offset = data.size();
                data.writeInt(bytes.length);
                data.write(bytes);
                keys.add(new Key(term.getBytes(StandardCharsets.UTF_8), offset));
                if (!reading.isEmpty() && !reading.equals(term)) {
                    keys.add(new Key(reading.getBytes(StandardCharsets.UTF_8), offset));
                }
                rows++;
            }
            reader.endArray();
        }
        return rows;
    }

    /** Older formats list glosses as trailing strings; store every row in the current shape. */
    private static JsonArray normalize(JsonArray row) throws IOException {
        if (row.size() < 5) {
            throw new IOException("a term row is too short");
        }
        if (row.size() >= 6 && row.get(5).isJsonArray()) {
            return row;
        }
        JsonArray glossary = new JsonArray();
        for (int i = 5; i < row.size(); i++) {
            glossary.add(row.get(i));
        }
        JsonArray normalized = new JsonArray();
        for (int i = 0; i < 5; i++) {
            normalized.add(row.get(i));
        }
        normalized.add(glossary);
        normalized.add(0);
        normalized.add("");
        return normalized;
    }

    private static void writeIndex(List<Key> keys, Path target) throws IOException {
        keys.sort((a, b) -> {
            int order = Arrays.compareUnsigned(a.bytes(), b.bytes());
            return order != 0 ? order : Long.compare(a.dataOffset(), b.dataOffset());
        });
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target), 1 << 16))) {
            out.writeInt(INDEX_MAGIC);
            out.writeInt(keys.size());
            // Table first, so a lookup can binary search it by position. Equal keys share their bytes.
            int blobOffset = 0;
            byte[] previous = null;
            List<byte[]> blob = new ArrayList<>();
            for (Key key : keys) {
                if (previous == null || !Arrays.equals(previous, key.bytes())) {
                    if (previous != null) {
                        blobOffset += previous.length;
                    }
                    blob.add(key.bytes());
                    previous = key.bytes();
                }
                out.writeInt(blobOffset);
                out.writeInt(key.bytes().length);
                out.writeLong(key.dataOffset());
            }
            for (byte[] bytes : blob) {
                out.write(bytes);
            }
        }
    }

    /** Parsed tag bank rows as {name, category}; tolerated missing so a tagless dictionary still works. */
    static List<String[]> readTags(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        List<String[]> tags = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonArray tag = element.getAsJsonArray();
            if (tag.size() >= 2) {
                tags.add(new String[]{tag.get(0).getAsString(), tag.get(1).getAsString()});
            }
        }
        return tags;
    }
}
