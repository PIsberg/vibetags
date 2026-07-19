package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.WriteCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutation-hardening for {@link WriteCache}. A false "unchanged" verdict or a lost/kept-stale
 * entry silently corrupts or fails to regenerate output, so the PIT survivors here matter:
 *
 * <ul>
 *   <li>"removed call to loadIfNeeded" — a mutator that makes a method operate on an
 *       unloaded (empty) cache, dropping everything persisted on disk. Each test below makes the
 *       mutated method the <em>first</em> call on a fresh instance whose backing file holds state,
 *       so skipping the load is observable.</li>
 *   <li>"replaced return value" / "negated conditional" on cacheKey, allCachedFilesStable, and
 *       the dirty-flag guards.</li>
 *   <li>the future-format {@code entries.clear()} safety.</li>
 * </ul>
 */
class WriteCacheMutationTest {

    private static Path seedWithEntry(Path cachePath, Path file, String body) {
        WriteCache seed = new WriteCache(cachePath);
        seed.recordWrite(file, body);
        seed.flush();
        return cachePath;
    }

    @Test
    void sidecarStamp_roundTripsAcrossInstances(@TempDir Path tmp) {
        // Kills: getSidecarStamp/setSidecarStamp loadIfNeeded, the setSidecarStamp dirty guard,
        // and the flush "sidecarStamp != null" guard.
        Path cp = tmp.resolve(".vibetags-cache");
        WriteCache c1 = new WriteCache(cp);
        c1.setSidecarStamp("stamp-X");
        c1.flush();

        WriteCache c2 = new WriteCache(cp);
        assertEquals("stamp-X", c2.getSidecarStamp(),
            "sidecar stamp must survive flush and reload");
    }

    @Test
    void setSidecarStamp_firstCall_preservesExistingEntries(@TempDir Path tmp) throws IOException {
        // Kills the "removed loadIfNeeded" mutant on setSidecarStamp: without the load, the
        // pre-existing entry is not read and is dropped by the subsequent flush.
        Path cp = tmp.resolve(".vibetags-cache");
        Path file = tmp.resolve("kept.md");
        Files.writeString(file, "body");
        seedWithEntry(cp, file, "body");

        WriteCache c = new WriteCache(cp);
        c.setSidecarStamp("s");            // first call must loadIfNeeded
        c.flush();

        WriteCache reload = new WriteCache(cp);
        assertTrue(reload.isUnchanged(file, "body"),
            "existing entry must survive a first-call setSidecarStamp + flush");
    }

    @Test
    void setBuildFingerprint_firstCall_preservesExistingEntries(@TempDir Path tmp) throws IOException {
        // Kills the "removed loadIfNeeded" mutant on setBuildFingerprint.
        Path cp = tmp.resolve(".vibetags-cache");
        Path file = tmp.resolve("kept.md");
        Files.writeString(file, "body");
        seedWithEntry(cp, file, "body");

        WriteCache c = new WriteCache(cp);
        c.setBuildFingerprint("fp");       // first call must loadIfNeeded
        c.flush();

        WriteCache reload = new WriteCache(cp);
        assertTrue(reload.isUnchanged(file, "body"),
            "existing entry must survive a first-call setBuildFingerprint + flush");
        assertEquals("fp", reload.getBuildFingerprint());
    }

    @Test
    void recordWrite_firstCall_preservesExistingEntries(@TempDir Path tmp) throws IOException {
        // Kills the "removed loadIfNeeded" mutant on recordWrite.
        Path cp = tmp.resolve(".vibetags-cache");
        Path a = tmp.resolve("a.md");
        Files.writeString(a, "aaa");
        seedWithEntry(cp, a, "aaa");

        Path b = tmp.resolve("b.md");
        Files.writeString(b, "bbb");
        WriteCache c = new WriteCache(cp);
        c.recordWrite(b, "bbb");           // first call must loadIfNeeded to keep a's entry
        c.flush();

        WriteCache reload = new WriteCache(cp);
        assertTrue(reload.isUnchanged(a, "aaa"), "pre-existing entry a must survive recordWrite");
        assertTrue(reload.isUnchanged(b, "bbb"), "new entry b must be recorded");
    }

    @Test
    void invalidate_firstCall_loadsThenRemovesAndPersists(@TempDir Path tmp) throws IOException {
        // Kills the "removed loadIfNeeded" mutant on invalidate AND the "entries.remove() != null"
        // dirty guard: both leave the removal un-persisted.
        Path cp = tmp.resolve(".vibetags-cache");
        Path a = tmp.resolve("a.md");
        Files.writeString(a, "aaa");
        seedWithEntry(cp, a, "aaa");

        WriteCache c = new WriteCache(cp);
        c.invalidate(a);                   // first call must load, remove, and mark dirty
        c.flush();

        WriteCache reload = new WriteCache(cp);
        assertFalse(reload.isUnchanged(a, "aaa"), "invalidated entry must not survive");
    }

    @Test
    void invalidate_unknownKey_doesNotDirtyTheCache(@TempDir Path tmp) {
        // Kills the negated "entries.remove() != null" guard in the other direction: removing a
        // key that was never present must not dirty the cache, so flush writes nothing.
        Path cp = tmp.resolve(".vibetags-cache");
        WriteCache c = new WriteCache(cp);
        c.invalidate(tmp.resolve("never-recorded.md"));
        c.flush();
        assertFalse(Files.exists(cp),
            "invalidating an unknown key must not create a cache file");
    }

    @Test
    void allCachedFilesStable_freshInstance_missingFile_returnsFalse(@TempDir Path tmp) throws IOException {
        // Kills the "removed loadIfNeeded" mutant (empty cache would wrongly report stable=true)
        // and the "replaced return false with true" mutant on allCachedFilesStable.
        Path cp = tmp.resolve(".vibetags-cache");
        writeRawCache(cp, 1, "ghost.md\tdeadbeef\t10\t20");

        WriteCache c = new WriteCache(cp);
        assertFalse(c.allCachedFilesStable(),
            "a cached entry whose file is missing must report unstable");
    }

    @Test
    void cacheKey_distinguishesDistinctFiles(@TempDir Path tmp) throws IOException {
        // Kills the "cacheKey replaced return value with \"\"" mutant: with a constant empty key
        // both files collapse to one entry, so invalidating one would evict the other.
        Path cp = tmp.resolve(".vibetags-cache");
        Path a = tmp.resolve("a.md");
        Path b = tmp.resolve("b.md");
        Files.writeString(a, "same");
        Files.writeString(b, "same");

        WriteCache c = new WriteCache(cp);
        c.recordWrite(a, "same");
        c.recordWrite(b, "same");
        c.invalidate(a);

        assertFalse(c.isUnchanged(a, "same"), "a was invalidated");
        assertTrue(c.isUnchanged(b, "same"), "invalidating a must not evict the distinct entry b");
    }

    @Test
    void futureFormatCache_withEarlierEntry_isDiscardedWholesale(@TempDir Path tmp) throws IOException {
        // Kills the "removed entries.clear()" mutant on the future-format guard: an entry parsed
        // before the newer-than-known format header must be dropped, not retained.
        Path cp = tmp.resolve(".vibetags-cache");
        Files.writeString(cp,
            "# VibeTags write cache. Auto-generated. Safe to delete.\n"
            + "early.md\tdeadbeef\t1\t2\n"
            + "# format: 2\n",   // FORMAT_VERSION is 1; 2 is "newer than this build knows"
            StandardCharsets.UTF_8);

        WriteCache c = new WriteCache(cp);
        assertEquals(0, c.size(),
            "a cache written in a newer format must be discarded wholesale");
    }

    private static void writeRawCache(Path cachePath, int formatVersion, String... entryLines)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VibeTags write cache. Auto-generated. Safe to delete.\n");
        sb.append("# format: ").append(formatVersion).append('\n');
        for (String line : entryLines) {
            sb.append(line).append('\n');
        }
        Files.writeString(cachePath, sb.toString(), StandardCharsets.UTF_8);
    }
}
