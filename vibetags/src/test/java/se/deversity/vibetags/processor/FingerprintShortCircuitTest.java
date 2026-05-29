package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.WriteCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the full fingerprint short-circuit path inside
 * {@code AIGuardrailProcessor.generateFiles()}.
 *
 * <p>The short-circuit fires when <em>all</em> of the following conditions hold:
 * <ol>
 *   <li>The annotation-set fingerprint matches the cached fingerprint.</li>
 *   <li>The sidecar-stamp matches the cached sidecar-stamp.</li>
 *   <li>Every previously-generated file is byte-stable on disk.</li>
 * </ol>
 *
 * <p>The tricky part is condition 2: in a single-module build the processor always
 * writes its sidecar file, bumping its mtime and thus the stamp.  To engineer a
 * matching stamp we:
 * <ol>
 *   <li>Compile once so the cache is primed with a valid fingerprint and file entries.</li>
 *   <li>Delete all {@code .vibetags-mod-*} sidecar files so
 *       {@code computeSidecarStamp()} returns {@code 0} on the next compile.</li>
 *   <li>Patch the persisted cache to store {@code sidecarStamp = "0"}, matching
 *       what the next compile will compute.</li>
 *   <li>Recompile — all three conditions now hold → the skip branch executes.</li>
 * </ol>
 */
class FingerprintShortCircuitTest {

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void shortCircuit_skipsGenerationWhenAllConditionsMatch(@TempDir Path tmp) throws Exception {
        // ---- First compile: prime the cache ----
        ProcessorTestHarness.withExampleSources(tmp);

        Path cursorRules = tmp.resolve(".cursorrules");
        Path claudeMd    = tmp.resolve("CLAUDE.md");
        assertTrue(Files.exists(cursorRules), ".cursorrules must exist after first compile");
        long cursorMtime = Files.getLastModifiedTime(cursorRules).toMillis();
        long claudeMtime = Files.getLastModifiedTime(claudeMd).toMillis();

        // ---- Engineer the matching sidecar-stamp ----
        // Delete all sidecar files so computeSidecarStamp() returns 0 on the next compile.
        deleteSidecars(tmp);

        // Patch the persisted WriteCache to store sidecarStamp = "0" while keeping
        // all file entries intact (WriteCache lazy-loads and preserves existing entries).
        Path cachePath = tmp.resolve(".vibetags-cache");
        WriteCache patchedCache = new WriteCache(cachePath);
        patchedCache.setSidecarStamp("0");
        patchedCache.flush();

        // Let the filesystem clock tick so a re-write — if one happened — would be visible.
        ProcessorTestHarness.awaitFilesystemTick(tmp);

        // ---- Second compile: same sources, sidecar-stamp now matches ----
        ProcessorTestHarness.withExampleSources(tmp);

        long cursorMtime2 = Files.getLastModifiedTime(cursorRules).toMillis();
        long claudeMtime2 = Files.getLastModifiedTime(claudeMd).toMillis();

        // If the short-circuit fired, no file was rewritten → mtimes unchanged.
        assertEquals(cursorMtime, cursorMtime2,
            ".cursorrules must not be rewritten when the fingerprint short-circuit fires");
        assertEquals(claudeMtime, claudeMtime2,
            "CLAUDE.md must not be rewritten when the fingerprint short-circuit fires");
    }

    @Test
    void shortCircuit_doesNotFire_whenAnnotationChanges(@TempDir Path tmp) throws Exception {
        // Compile with one @AILocked reason.
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"original reason\")\n" +
            "public class A {}\n");
        h1.compile();

        Path cursorRules = tmp.resolve(".cursorrules");
        long mtime1 = Files.getLastModifiedTime(cursorRules).toMillis();

        // Engineer matching sidecar stamp (delete sidecars, set stamp to "0").
        deleteSidecars(tmp);
        WriteCache patchedCache = new WriteCache(tmp.resolve(".vibetags-cache"));
        patchedCache.setSidecarStamp("0");
        patchedCache.flush();

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        // Recompile with a DIFFERENT reason → fingerprint changes → short-circuit must NOT fire.
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"completely different reason\")\n" +
            "public class A {}\n");
        h2.compile();

        long mtime2 = Files.getLastModifiedTime(cursorRules).toMillis();

        assertTrue(mtime2 > mtime1,
            ".cursorrules must be rewritten when annotation attributes change (fingerprint differs)");
    }

    @Test
    void shortCircuit_doesNotFire_whenOutputFileDeleted(@TempDir Path tmp) throws Exception {
        // Compile once to prime the cache.
        ProcessorTestHarness.withExampleSources(tmp);

        // Engineer matching sidecar stamp.
        deleteSidecars(tmp);
        WriteCache patchedCache = new WriteCache(tmp.resolve(".vibetags-cache"));
        patchedCache.setSidecarStamp("0");
        patchedCache.flush();

        // Delete a generated output file — allCachedFilesStable() must return false.
        Path cursorRules = tmp.resolve(".cursorrules");
        Files.delete(cursorRules);

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        // Recompile — deleted file means stability check fails → short-circuit must NOT fire.
        ProcessorTestHarness.withExampleSources(tmp);

        assertTrue(Files.exists(cursorRules),
            ".cursorrules must be recreated when deleted (short-circuit must not fire)");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static void deleteSidecars(Path root) throws IOException {
        List<Path> sidecars;
        try (var stream = Files.list(root)) {
            sidecars = stream
                .filter(p -> p.getFileName().toString().startsWith(".vibetags-mod-"))
                .collect(Collectors.toList());
        }
        for (Path p : sidecars) {
            Files.deleteIfExists(p);
        }
    }
}
