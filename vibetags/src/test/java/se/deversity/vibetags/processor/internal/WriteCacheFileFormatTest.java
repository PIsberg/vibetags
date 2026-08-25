package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code .vibetags-cache} does when the file on disk is not the one this processor wrote.
 *
 * <p>The cache decides whether a build regenerates at all, so its failure mode is asymmetric. A
 * cache that is discarded too eagerly costs a rebuild nobody notices. A cache that is trusted when
 * it should not be leaves stale generated files on disk with no warning and no way to tell from
 * the build log, which is the one outcome the whole marker-and-fingerprint design exists to
 * prevent. Every case here is therefore a "does it correctly refuse to trust this" case.
 *
 * <p>The file is read on a machine where a different processor version, a different IDE, or a
 * half-finished write may have produced it, so none of these inputs is hypothetical.
 */
class WriteCacheFileFormatTest {

    private static final String CACHE = ".vibetags-cache";

    private static Path writeCacheFile(Path dir, String... lines) throws IOException {
        Path file = dir.resolve(CACHE);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** A well-formed entry line: path, hash, size, mtime, tab-separated. */
    private static String entry(String path, String hash, long size, long mtime) {
        return path + "\t" + hash + "\t" + size + "\t" + mtime;
    }

    @Test
    void anAbsentCacheFileReadsAsEmptyRatherThanFailing(@TempDir Path dir) {
        WriteCache cache = new WriteCache(dir.resolve(CACHE));
        assertNull(cache.getBuildFingerprint());
        assertNull(cache.getSidecarStamp());
        assertEquals(0, cache.size());
        assertTrue(cache.allCachedFilesStable(),
            "an empty cache has no on-disk state to have drifted");
    }

    @Test
    void headersAreReadBack(@TempDir Path dir) throws IOException {
        Path file = writeCacheFile(dir,
            "# format: 1",
            "# fingerprint: abcd1234",
            "# sidecar-stamp: ffee0011",
            "# some other comment nobody parses",
            entry("CLAUDE.md", "0f0f0f0f", 12, 1000L));

        WriteCache cache = new WriteCache(file);
        assertEquals("abcd1234", cache.getBuildFingerprint());
        assertEquals("ffee0011", cache.getSidecarStamp());
        assertEquals(1, cache.size());
    }

    @Test
    void aCacheFromANewerFormatIsDiscardedWholesale(@TempDir Path dir) throws IOException {
        // A newer processor may write a line shape this version would misread. Reading half of it
        // is worse than reading none: the cache is a pure optimisation and rebuilds on next write.
        Path file = writeCacheFile(dir,
            "# format: 99",
            "# fingerprint: abcd1234",
            "# sidecar-stamp: ffee0011",
            entry("CLAUDE.md", "0f0f0f0f", 12, 1000L));

        WriteCache cache = new WriteCache(file);
        assertNull(cache.getBuildFingerprint(),
            "a future-format fingerprint must not short-circuit this version's build");
        assertNull(cache.getSidecarStamp());
        assertEquals(0, cache.size());
    }

    @Test
    void anUnparseableFormatHeaderIsTreatedLikeAFutureFormat(@TempDir Path dir) throws IOException {
        // "# format: " followed by anything but a number means the file was not written by a
        // version whose layout is known. Unknown is the same risk as newer.
        Path file = writeCacheFile(dir,
            "# format: v2-beta",
            "# fingerprint: abcd1234",
            entry("CLAUDE.md", "0f0f0f0f", 12, 1000L));

        WriteCache cache = new WriteCache(file);
        assertNull(cache.getBuildFingerprint());
        assertEquals(0, cache.size());
    }

    @Test
    void headersAfterTheFormatLineSurviveAnOlderFormat(@TempDir Path dir) throws IOException {
        Path file = writeCacheFile(dir,
            "# format: 0",
            "# fingerprint: abcd1234");

        assertEquals("abcd1234", new WriteCache(file).getBuildFingerprint(),
            "an older format is readable by definition: this version wrote it");
    }

    @Test
    void blankHeaderValuesAreIgnoredRatherThanStoredAsEmpty(@TempDir Path dir) throws IOException {
        // An empty fingerprint would compare unequal to every real one and thus never
        // short-circuit, which is harmless — but an empty *stamp* read back as "" rather than null
        // is a value the caller would compare against, so neither may be stored blank.
        Path file = writeCacheFile(dir,
            "# fingerprint:    ",
            "# sidecar-stamp: ",
            "# context:   ");

        WriteCache cache = new WriteCache(file);
        assertNull(cache.getBuildFingerprint());
        assertNull(cache.getSidecarStamp());
    }

    @Test
    void entryLinesWithTooFewFieldsAreSkipped(@TempDir Path dir) throws IOException {
        // A half-written line is what a build killed mid-flush leaves behind.
        Path file = writeCacheFile(dir,
            "no-tabs-at-all",
            "one\ttab",
            "two\ttabs\there",
            entry("CLAUDE.md", "0f0f0f0f", 12, 1000L));

        assertEquals(1, new WriteCache(file).size(),
            "only the complete line is an entry; the truncated ones must not become half-entries");
    }

    @Test
    void anEntryWithANonNumericSizeIsSkipped(@TempDir Path dir) throws IOException {
        Path file = writeCacheFile(dir,
            "CLAUDE.md\t0f0f0f0f\tnot-a-number\t1000",
            entry("AGENTS.md", "0f0f0f0f", 12, 1000L));

        assertEquals(1, new WriteCache(file).size());
    }

    @Test
    void aStoredContextThatDiffersHidesTheFingerprint(@TempDir Path dir) throws IOException {
        // -Avibetags.project and -Avibetags.module change the rendered output without changing the
        // annotations, so the same fingerprint under a different context must not short-circuit.
        Path file = writeCacheFile(dir,
            "# fingerprint: abcd1234",
            "# context: ctx-one");

        WriteCache sameContext = new WriteCache(file);
        sameContext.bindContext("ctx-one");
        assertEquals("abcd1234", sameContext.getBuildFingerprint());

        WriteCache otherContext = new WriteCache(file);
        otherContext.bindContext("ctx-two");
        assertNull(otherContext.getBuildFingerprint(),
            "an option edit must regenerate rather than reuse the previous context's output");
    }

    @Test
    void anInstanceThatNeverBindsAContextKeepsTheStoredFingerprint(@TempDir Path dir) throws IOException {
        Path file = writeCacheFile(dir,
            "# fingerprint: abcd1234",
            "# context: ctx-one");

        assertEquals("abcd1234", new WriteCache(file).getBuildFingerprint(),
            "not binding a context is not the same as binding a different one");
    }

    @Test
    void anAbsoluteEntryPathIsStoredRelativeToTheCacheDirectory(@TempDir Path dir) throws IOException {
        // Older caches recorded absolute paths. Reading one back has to key it the same way this
        // version keys it, or every entry misses and the stability check reports drift forever.
        Path generated = dir.resolve("CLAUDE.md");
        Files.writeString(generated, "hello", StandardCharsets.UTF_8);
        long size = Files.size(generated);
        long mtime = Files.getLastModifiedTime(generated).toMillis();

        Path file = writeCacheFile(dir,
            entry(generated.toAbsolutePath().toString(), "0f0f0f0f", size, mtime));

        WriteCache cache = new WriteCache(file);
        assertEquals(1, cache.size());
        assertTrue(cache.allCachedFilesStable(),
            "the absolute path must resolve back to the same file, or the entry can never match");
    }

    @Test
    void aCachedFileThatChangedOnDiskIsReportedAsDrifted(@TempDir Path dir) throws IOException {
        // The control for the test above: the stability check has to be able to say "no".
        Path generated = dir.resolve("CLAUDE.md");
        Files.writeString(generated, "hello", StandardCharsets.UTF_8);

        Path file = writeCacheFile(dir,
            entry("CLAUDE.md", "0f0f0f0f", Files.size(generated) + 100, 1000L));

        assertFalse(new WriteCache(file).allCachedFilesStable(),
            "a file whose size no longer matches is exactly the manual edit the check exists for");
    }

    @Test
    void aCachedFileThatVanishedIsReportedAsDrifted(@TempDir Path dir) throws IOException {
        Path file = writeCacheFile(dir, entry("deleted.md", "0f0f0f0f", 5, 1000L));

        assertFalse(new WriteCache(file).allCachedFilesStable(),
            "a deleted output must not be skipped as unchanged; that is how it stays deleted");
    }

    @Test
    void flushRoundTripsThroughTheFileItJustWrote(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(CACHE);
        Path generated = dir.resolve("CLAUDE.md");
        Files.writeString(generated, "body", StandardCharsets.UTF_8);

        WriteCache first = new WriteCache(file);
        first.setBuildFingerprint("aaaa1111");
        first.setSidecarStamp("bbbb2222");
        first.bindContext("ctx");
        first.recordWrite(generated, "body");
        first.flush();

        assertTrue(Files.isRegularFile(file), "flush must have created the cache file");

        WriteCache reread = new WriteCache(file);
        reread.bindContext("ctx");
        assertEquals("aaaa1111", reread.getBuildFingerprint());
        assertEquals("bbbb2222", reread.getSidecarStamp());
        assertEquals(1, reread.size());
    }

    @Test
    void aRecordedWriteIsRecognisedAsUnchangedOnlyForTheSameBody(@TempDir Path dir) throws IOException {
        Path generated = dir.resolve("CLAUDE.md");
        Files.writeString(generated, "body", StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(dir.resolve(CACHE));
        cache.recordWrite(generated, "body");

        assertTrue(cache.isUnchanged(generated, "body"));
        assertFalse(cache.isUnchanged(generated, "different body"),
            "a changed body must be written, whatever the file's size and mtime say");
    }

    @Test
    void invalidateForgetsAnEntry(@TempDir Path dir) throws IOException {
        Path generated = dir.resolve("CLAUDE.md");
        Files.writeString(generated, "body", StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(dir.resolve(CACHE));
        cache.recordWrite(generated, "body");
        assertEquals(1, cache.size());

        cache.invalidate(generated);
        assertEquals(0, cache.size());
        assertFalse(cache.isUnchanged(generated, "body"),
            "an invalidated file must be rewritten even though its bytes still match");
    }

    @Test
    void recordInputForAFileThatDoesNotExistDropsAnyStaleEntry(@TempDir Path dir) throws IOException {
        // A watched input that has been deleted must not keep a stale entry claiming it is stable.
        Path input = dir.resolve("gone.md");
        Files.writeString(input, "x", StandardCharsets.UTF_8);

        WriteCache cache = new WriteCache(dir.resolve(CACHE));
        cache.recordInput(input);
        assertEquals(1, cache.size());

        Files.delete(input);
        cache.recordInput(input);
        assertEquals(0, cache.size());
    }

    @Test
    void aCacheDirectlyInTheWorkingDirectoryStillResolvesItsEntries(@TempDir Path dir) throws IOException {
        // cachePath.getParent() is null for a bare file name; the constructor has to fall back to
        // the working directory rather than NPE on the first entry lookup.
        WriteCache cache = new WriteCache(Path.of(CACHE));
        assertEquals(0, cache.size());
        assertTrue(cache.allCachedFilesStable());
        assertNotNull(cache, "constructing from a parentless path must not throw");
    }
}
