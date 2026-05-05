package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.WriteCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WriteCache}: hit, miss after edit, miss after delete,
 * persistence across instances, corrupt-cache fallback.
 */
class WriteCacheTest {

    @Test
    void emptyCache_returnsFalseForUnknownFile(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        assertFalse(cache.isUnchanged(file, "hello"),
            "fresh cache must miss");
    }

    @Test
    void recordThenLookup_sameContent_hits(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        String body = "hello world";
        Files.writeString(file, body);

        cache.recordWrite(file, body);
        assertTrue(cache.isUnchanged(file, body),
            "cache must hit on same body when file is unchanged");
    }

    @Test
    void differentBody_misses(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello");

        assertFalse(cache.isUnchanged(file, "world"),
            "cache must miss when body differs");
    }

    @Test
    void externalEdit_invalidatesViaMtime(@TempDir Path tmp) throws IOException, InterruptedException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello");

        // Bump the mtime to simulate user edit; content stays the same length so size matches.
        FileTime later = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000);
        Files.setLastModifiedTime(file, later);

        assertFalse(cache.isUnchanged(file, "hello"),
            "mtime change must invalidate the cache entry");
    }

    @Test
    void externalDelete_misses(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello");
        Files.delete(file);

        assertFalse(cache.isUnchanged(file, "hello"),
            "missing file must miss (no IOException leaks out)");
    }

    @Test
    void flushAndReload_preservesEntries(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");

        WriteCache writer = new WriteCache(cachePath);
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "persistent");
        writer.recordWrite(file, "persistent");
        writer.flush();

        assertTrue(Files.exists(cachePath), ".vibetags-cache should be persisted on flush");

        // Fresh instance — should pick up the persisted entry.
        WriteCache reader = new WriteCache(cachePath);
        assertTrue(reader.isUnchanged(file, "persistent"),
            "second instance must hit on persisted body");
        assertFalse(reader.isUnchanged(file, "different"),
            "second instance must miss on different body");
    }

    @Test
    void invalidate_removesEntry(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello");
        assertTrue(cache.isUnchanged(file, "hello"));

        cache.invalidate(file);
        assertFalse(cache.isUnchanged(file, "hello"),
            "invalidate must clear the entry");
    }

    @Test
    void corruptCacheFile_isIgnoredGracefully(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.writeString(cachePath,
            "# header\nthis-is-not\ttab-separated-properly\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");

        // Corrupt rows are skipped — cache acts empty for unknown paths.
        assertFalse(cache.isUnchanged(file, "hello"));

        // New writes still work.
        cache.recordWrite(file, "hello");
        assertTrue(cache.isUnchanged(file, "hello"));
    }

    @Test
    void flush_isNoopWhenNothingChanged(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");
        WriteCache cache = new WriteCache(cachePath);
        cache.flush();
        assertFalse(Files.exists(cachePath),
            "flushing an unmodified cache must not create a file");
    }

    @Test
    void sizeChange_invalidates(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello");

        // Append to file directly — size changes, mtime may also change.
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        assertFalse(cache.isUnchanged(file, "hello"),
            "size change must invalidate the cache entry");
    }
}
