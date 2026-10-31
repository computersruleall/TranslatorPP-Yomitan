package net.psunset.translatorpp.yomitan.local;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An imported dictionary, memory-mapped so a million-entry index costs no heap.
 * Not thread safe: the engine uses each one from its single worker thread. Mapped
 * through an arena because a mapping must be released before Windows lets the
 * files be deleted.
 */
final class DictionaryFile implements AutoCloseable {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int HEADER = 8;
    private static final int ENTRY = 16;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment index;
    private final MemorySegment data;
    private final int count;
    private final long blobStart;
    private final Map<String, String> tagCategories = new HashMap<>();

    DictionaryFile(Path directory) throws IOException {
        try {
            index = map(directory.resolve(DictionaryImporter.INDEX_FILE));
            data = map(directory.resolve(DictionaryImporter.DATA_FILE));
            if (index.byteSize() < HEADER || index.get(INT, 0) != DictionaryImporter.INDEX_MAGIC) {
                throw new IOException("dictionary index is damaged; remove and re-import it");
            }
            count = index.get(INT, 4);
            blobStart = HEADER + (long) count * ENTRY;
            for (String[] tag : DictionaryImporter.readTags(directory.resolve(DictionaryImporter.TAGS_FILE))) {
                tagCategories.putIfAbsent(tag[0], tag[1]);
            }
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private MemorySegment map(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    /** Offsets of the rows whose headword or reading is exactly {@code key}, at most {@code limit}. */
    List<Long> find(String key, int limit) {
        byte[] wanted = key.getBytes(StandardCharsets.UTF_8);
        int low = 0;
        int high = count;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (compareAt(middle, wanted) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        List<Long> offsets = new ArrayList<>();
        for (int i = low; i < count && offsets.size() < limit && compareAt(i, wanted) == 0; i++) {
            offsets.add(index.get(LONG, HEADER + (long) i * ENTRY + 8));
        }
        return offsets;
    }

    private int compareAt(int entry, byte[] wanted) {
        long table = HEADER + (long) entry * ENTRY;
        long start = blobStart + index.get(INT, table);
        int length = index.get(INT, table + 4);
        int shared = Math.min(length, wanted.length);
        for (int i = 0; i < shared; i++) {
            int a = index.get(ValueLayout.JAVA_BYTE, start + i) & 0xFF;
            int b = wanted[i] & 0xFF;
            if (a != b) {
                return a - b;
            }
        }
        return length - wanted.length;
    }

    /** The JSON of the row at {@code offset}. */
    String row(long offset) {
        int length = data.get(INT, offset);
        byte[] bytes = new byte[length];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset + 4, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    String tagCategory(String tag) {
        return tagCategories.getOrDefault(tag, "");
    }

    @Override
    public void close() {
        arena.close();
    }
}
