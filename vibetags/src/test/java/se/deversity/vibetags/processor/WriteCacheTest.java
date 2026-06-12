package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.WriteCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    void flush_writesFormatVersionHeader(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");
        WriteCache cache = new WriteCache(cachePath);
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello");
        cache.flush();

        String content = Files.readString(cachePath, StandardCharsets.UTF_8);
        assertTrue(content.contains("# format: 1\n"),
            "flushed cache must carry the format-version header: " + content);
    }

    @Test
    void futureFormatVersion_discardsCacheWholesale(@TempDir Path tmp) throws IOException {
        // Simulate a cache written by a NEWER processor whose line format we may not be
        // able to parse. The only safe response is to discard it and rebuild.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");

        // Build a valid v1 entry line, then stamp the file as a future format.
        WriteCache seed = new WriteCache(cachePath);
        seed.recordWrite(file, "hello");
        seed.setBuildFingerprint("cafebabe");
        seed.flush();
        String v1 = Files.readString(cachePath, StandardCharsets.UTF_8);
        Files.writeString(cachePath, v1.replace("# format: 1", "# format: 99"),
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertEquals(0, cache.size(), "future-format cache must be discarded, not parsed");
        assertNull(cache.getBuildFingerprint(),
            "fingerprint from a future-format cache must not be trusted");
        assertFalse(cache.isUnchanged(file, "hello"),
            "entries from a future-format cache must not produce hits");

        // The cache rebuilds normally from here.
        cache.recordWrite(file, "hello");
        assertTrue(cache.isUnchanged(file, "hello"));
    }

    @Test
    void legacyCacheWithoutFormatHeader_stillLoads(@TempDir Path tmp) throws IOException {
        // Pre-1.0 caches have no "# format:" header but use the identical line format;
        // they must keep loading so the first 1.0 compile doesn't lose the whole cache.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");

        WriteCache seed = new WriteCache(cachePath);
        seed.recordWrite(file, "hello");
        seed.flush();
        String content = Files.readString(cachePath, StandardCharsets.UTF_8);
        String legacy = content.replace("# format: 1\n", "");
        Files.writeString(cachePath, legacy, StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertTrue(cache.isUnchanged(file, "hello"),
            "legacy header-less cache must load as format 1");
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

    @Test
    void recordWrite_onMissingFile_dropsEntry(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path neverExisted = tmp.resolve("does-not-exist.md");

        // Pre-populate so the entry exists, then call recordWrite on a now-missing file.
        Files.writeString(neverExisted, "hello");
        cache.recordWrite(neverExisted, "hello");
        assertEquals(1, cache.size());

        Files.delete(neverExisted);
        cache.recordWrite(neverExisted, "hello");
        assertEquals(0, cache.size(),
            "recordWrite must drop the entry when stat fails on the file we just attempted to write");
    }

    @Test
    void corruptNumericRow_isSkipped(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");
        // Three valid tabs but bogus numeric fields — exercises the NumberFormatException catch.
        Files.writeString(cachePath,
            "# header\n/some/path\tabcd1234\tnot-a-number\talso-not-a-number\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        // Touching the cache forces loadIfNeeded; the corrupt row must be skipped silently.
        assertFalse(cache.isUnchanged(tmp.resolve("foo.md"), "anything"));
        assertEquals(0, cache.size(), "corrupt numeric row must be ignored");
    }

    @Test
    void cachePathIsDirectory_loadFailsGracefully(@TempDir Path tmp) throws IOException {
        // Create a directory where the cache file would live — readAllLines will throw IOException
        // (not NoSuchFileException), exercising the outer catch in loadIfNeeded.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.createDirectory(cachePath);

        WriteCache cache = new WriteCache(cachePath);
        // size() forces loadIfNeeded; the IOException catch must clear the entries map without throwing.
        assertEquals(0, cache.size());
    }

    @Test
    void flush_swallowsIOException_whenParentIsFile(@TempDir Path tmp) throws IOException {
        // Cache path's parent is a regular file → Files.createDirectories will fail with
        // FileAlreadyExistsException (an IOException). flush() must swallow it silently.
        Path blocker = tmp.resolve("blocker-file");
        Files.writeString(blocker, "I am a regular file, not a directory");
        Path cachePath = blocker.resolve("cache");

        WriteCache cache = new WriteCache(cachePath);
        Path file = tmp.resolve("foo.md");
        Files.writeString(file, "hello");
        cache.recordWrite(file, "hello"); // marks dirty=true

        assertDoesNotThrow(cache::flush,
            "flush must swallow IOException when the cache path is unwritable");
        assertFalse(Files.exists(cachePath),
            "cache file was not created — but flush did not throw");
    }

    @Test
    void freshCache_hasNoFingerprint(@TempDir Path tmp) {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        assertNull(cache.getBuildFingerprint(),
            "fresh cache must report null fingerprint until one is recorded");
    }

    @Test
    void setFingerprint_persistsAcrossInstances(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");

        WriteCache writer = new WriteCache(cachePath);
        writer.setBuildFingerprint("deadbeef");
        // Need at least one entry OR the dirty flag for flush to write — setBuildFingerprint
        // must mark the cache dirty on its own.
        writer.flush();
        assertTrue(Files.exists(cachePath), "setBuildFingerprint alone must mark cache dirty so flush writes");

        WriteCache reader = new WriteCache(cachePath);
        assertEquals("deadbeef", reader.getBuildFingerprint(),
            "fingerprint must round-trip through the cache file");
    }

    @Test
    void setFingerprint_ignoredWhenIdentical(@TempDir Path tmp) throws IOException {
        Path cachePath = tmp.resolve(".vibetags-cache");
        WriteCache cache = new WriteCache(cachePath);

        cache.setBuildFingerprint("aaaa1111");
        cache.flush();
        long firstMtime = Files.getLastModifiedTime(cachePath).toMillis();

        // Re-setting the same value must not mark dirty → flush stays a no-op.
        cache.setBuildFingerprint("aaaa1111");
        cache.flush();
        long secondMtime = Files.getLastModifiedTime(cachePath).toMillis();
        assertEquals(firstMtime, secondMtime,
            "no-op setBuildFingerprint must not rewrite the cache file");
    }

    @Test
    void allCachedFilesStable_emptyCache_returnsTrue(@TempDir Path tmp) {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        assertTrue(cache.allCachedFilesStable(),
            "an empty cache has no entries to invalidate");
    }

    @Test
    void allCachedFilesStable_unchangedFiles_returnsTrue(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path a = tmp.resolve("a.md");
        Path b = tmp.resolve("b.md");
        Files.writeString(a, "alpha");
        Files.writeString(b, "beta");
        cache.recordWrite(a, "alpha");
        cache.recordWrite(b, "beta");

        assertTrue(cache.allCachedFilesStable(),
            "all cached files unchanged → stability check returns true");
    }

    @Test
    void allCachedFilesStable_externallyModified_returnsFalse(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("victim.md");
        Files.writeString(file, "original");
        cache.recordWrite(file, "original");

        // External modification: bump mtime so the cached entry no longer matches.
        FileTime later = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000);
        Files.setLastModifiedTime(file, later);

        assertFalse(cache.allCachedFilesStable(),
            "modified mtime must invalidate the stability check");
    }

    @Test
    void allCachedFilesStable_deletedFile_returnsFalse(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        Path file = tmp.resolve("victim.md");
        Files.writeString(file, "doomed");
        cache.recordWrite(file, "doomed");

        Files.delete(file);

        assertFalse(cache.allCachedFilesStable(),
            "deleted cached file must invalidate the stability check");
    }

    @Test
    void size_reflectsRecordedEntries(@TempDir Path tmp) throws IOException {
        WriteCache cache = new WriteCache(tmp.resolve(".vibetags-cache"));
        assertEquals(0, cache.size(), "fresh cache reports zero entries");

        Path a = tmp.resolve("a.md");
        Path b = tmp.resolve("b.md");
        Files.writeString(a, "a");
        Files.writeString(b, "b");
        cache.recordWrite(a, "a");
        cache.recordWrite(b, "b");

        assertEquals(2, cache.size(), "size reflects recorded entries");
        cache.invalidate(a);
        assertEquals(1, cache.size(), "size decrements after invalidate");
    }

    // -----------------------------------------------------------------------
    // Cache-file parsing edge cases (covers loadIfNeeded branches)
    // -----------------------------------------------------------------------

    @Test
    void load_emptyLine_isSkipped(@TempDir Path tmp) throws IOException {
        // Covers the `if (line.isEmpty()) continue;` branch in loadIfNeeded.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.writeString(cachePath,
            "# fingerprint: abc12345\n"
                + "\n"                                           // empty line
                + ".cursorrules\tabc\t42\t1000\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertEquals(1, cache.size(), "empty line must be silently skipped; valid entry must load");
        assertEquals("abc12345", cache.getBuildFingerprint());
    }

    @Test
    void load_fingerprintPrefixWithEmptyValue_isIgnored(@TempDir Path tmp) throws IOException {
        // Covers the `if (!fp.isEmpty()) buildFingerprint = fp;` false branch:
        // a "# fingerprint: " line whose value is blank must not overwrite a prior fingerprint.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.writeString(cachePath,
            "# fingerprint: \n"      // blank value — must not be stored
                + ".cursorrules\tabc\t42\t1000\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertNull(cache.getBuildFingerprint(),
            "blank fingerprint value must leave fingerprint as null");
    }

    @Test
    void load_sidecarStampPrefixWithEmptyValue_isIgnored(@TempDir Path tmp) throws IOException {
        // Covers the `if (!st.isEmpty()) sidecarStamp = st;` false branch.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.writeString(cachePath,
            "# sidecar-stamp: \n"    // blank value — must not be stored
                + ".cursorrules\tabc\t42\t1000\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertNull(cache.getSidecarStamp(),
            "blank sidecar-stamp value must leave stamp as null");
    }

    @Test
    void load_lineWithNoTab_isSkipped(@TempDir Path tmp) throws IOException {
        // Covers the `if (t1 < 0) continue;` branch in loadIfNeeded.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.writeString(cachePath,
            "no-tab-in-this-line\n"
                + ".cursorrules\tabc\t42\t1000\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertEquals(1, cache.size(), "line without any tab must be skipped; valid entry must load");
    }

    @Test
    void load_lineWithOnlyTwoTabs_isSkipped(@TempDir Path tmp) throws IOException {
        // Covers the `if (t3 < 0) continue;` branch: the valid format needs 3 tabs
        // (path, hash, size, mtime). A line with only 2 tabs is malformed.
        Path cachePath = tmp.resolve(".vibetags-cache");
        Files.writeString(cachePath,
            "only\ttwo\ttabs\n"                                  // 2 tabs → t3 < 0
                + ".cursorrules\tabc\t42\t1000\n",
            StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(cachePath);
        assertEquals(1, cache.size(), "line with only two tabs must be skipped; valid entry must load");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void fileOnDifferentDrive_thanCacheRoot_doesNotThrow() {
        // Regression: on Windows a project (and thus the cache root) on one drive plus an output
        // dir / @TempDir on another makes rootDir.relativize(file) throw
        // "IllegalArgumentException: 'other' has different root". relativize is purely lexical,
        // so distinct drive letters reproduce it without either drive needing to exist.
        WriteCache cache = new WriteCache(Paths.get("C:\\vt-cache-root\\.vibetags-cache"));
        Path onAnotherDrive = Paths.get("D:\\vt-output\\CLAUDE.md");

        assertFalse(assertDoesNotThrow(() -> cache.isUnchanged(onAnotherDrive, "body")),
            "a file on a different drive must miss the cache, not crash");
        assertDoesNotThrow(() -> cache.invalidate(onAnotherDrive),
            "invalidate must tolerate a different-drive path");
    }
}
