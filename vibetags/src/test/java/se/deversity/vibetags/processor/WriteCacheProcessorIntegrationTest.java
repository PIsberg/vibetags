package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the per-output-file write cache wired through {@link AIGuardrailProcessor}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The {@code .vibetags-cache} sidecar is created on first compile.</li>
 *   <li>It contains entries for the platform files we wrote.</li>
 *   <li>A second compile against unchanged sources keeps file mtimes stable
 *       (cache fast-path skipped the read+write).</li>
 *   <li>Editing a generated file externally invalidates the cache for that file
 *       — the next compile re-reads and re-writes it.</li>
 * </ul>
 */
class WriteCacheProcessorIntegrationTest {

    @AfterEach
    void releaseLogFile() {
        // The processor opens vibetags.log via Logback; release it before @TempDir tries to delete the dir.
        VibeTagsLogger.shutdown();
    }

    @Test
    void firstCompile_writesCacheFile(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = ProcessorTestHarness.withExampleSources(tmp);

        Path cache = h.root().resolve(".vibetags-cache");
        assertTrue(Files.exists(cache),
            ".vibetags-cache should be created at the project root after a successful compile");

        String content = Files.readString(cache, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("# VibeTags write cache"),
            "cache file should carry its disposable-comment header");

        // Should contain entries for the headline platform files.
        assertTrue(content.contains(".cursorrules"),
            "cache should record .cursorrules: " + content);
        assertTrue(content.contains("CLAUDE.md"),
            "cache should record CLAUDE.md: " + content);
    }

    @Test
    void secondCompile_unchangedSources_doesNotRewriteFiles(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = ProcessorTestHarness.withExampleSources(tmp);

        // Capture mtimes of every generated platform file.
        Path cursor = h.root().resolve(".cursorrules");
        Path claude = h.root().resolve("CLAUDE.md");
        long cursorMtime1 = Files.getLastModifiedTime(cursor).toMillis();
        long claudeMtime1 = Files.getLastModifiedTime(claude).toMillis();
        assertTrue(cursorMtime1 > 0);

        // Wait long enough that filesystem mtime resolution would register a change
        // if we re-wrote the file (FAT/Windows mtime resolution can be 1-2 s).
        Thread.sleep(1500);

        // Recompile against the same sources — same processor instance not reused, but
        // .vibetags-cache survives on disk.
        ProcessorTestHarness h2 = ProcessorTestHarness.withExampleSources(tmp);

        long cursorMtime2 = Files.getLastModifiedTime(cursor).toMillis();
        long claudeMtime2 = Files.getLastModifiedTime(claude).toMillis();

        assertEquals(cursorMtime1, cursorMtime2,
            ".cursorrules mtime must be unchanged after second compile (cache hit skipped the write)");
        assertEquals(claudeMtime1, claudeMtime2,
            "CLAUDE.md mtime must be unchanged after second compile (cache hit skipped the write)");
    }

    @Test
    void externalEdit_invalidatesCacheForThatFile(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h = ProcessorTestHarness.withExampleSources(tmp);
        Path cursor = h.root().resolve(".cursorrules");
        long mtime1 = Files.getLastModifiedTime(cursor).toMillis();

        Thread.sleep(1500);

        // Simulate user editing the file: append a line at the very top (outside the marker block).
        // The processor's read-compare path will rebuild a 'finalContent' that no longer matches
        // the current file → it must rewrite, bumping mtime.
        String original = Files.readString(cursor, StandardCharsets.UTF_8);
        Files.writeString(cursor, "# user-added comment\n" + original, StandardCharsets.UTF_8);
        long editMtime = Files.getLastModifiedTime(cursor).toMillis();
        assertTrue(editMtime > mtime1, "edit must bump mtime");

        // Recompile — cache now disagrees with disk; processor must re-read and re-write.
        Thread.sleep(1500);
        ProcessorTestHarness.withExampleSources(tmp);
        long mtime3 = Files.getLastModifiedTime(cursor).toMillis();

        assertTrue(mtime3 > editMtime,
            ".cursorrules must be rewritten after external edit; got mtime " + mtime3 + " vs edit " + editMtime);

        // The user comment should be preserved — VibeTags only updates within its marker block.
        String afterRecompile = Files.readString(cursor, StandardCharsets.UTF_8);
        assertTrue(afterRecompile.contains("# user-added comment"),
            "user content above the marker block must be preserved");
    }
}
