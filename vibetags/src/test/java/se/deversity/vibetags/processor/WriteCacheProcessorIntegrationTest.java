package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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
@Tag("e2e")
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

        // Let the filesystem clock tick so that a re-write — if one happened — would be visible.
        ProcessorTestHarness.awaitFilesystemTick(tmp);

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

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        // Simulate user editing the file: append a line at the very top (outside the marker block).
        // The processor's read-compare path will rebuild a 'finalContent' that no longer matches
        // the current file → it must rewrite, bumping mtime.
        String original = Files.readString(cursor, StandardCharsets.UTF_8);
        Files.writeString(cursor, "# user-added comment\n" + original, StandardCharsets.UTF_8);
        long editMtime = Files.getLastModifiedTime(cursor).toMillis();
        assertTrue(editMtime > mtime1, "edit must bump mtime");

        // Recompile — cache now disagrees with disk; processor must re-read and re-write.
        ProcessorTestHarness.awaitFilesystemTick(tmp);
        ProcessorTestHarness.withExampleSources(tmp);
        long mtime3 = Files.getLastModifiedTime(cursor).toMillis();

        assertTrue(mtime3 > editMtime,
            ".cursorrules must be rewritten after external edit; got mtime " + mtime3 + " vs edit " + editMtime);

        // The user comment should be preserved — VibeTags only updates within its marker block.
        String afterRecompile = Files.readString(cursor, StandardCharsets.UTF_8);
        assertTrue(afterRecompile.contains("# user-added comment"),
            "user content above the marker block must be preserved");
    }

    /**
     * A marker file that is already current on the first cached build is still a file this
     * processor owns. That is the fresh-clone shape: the committed CLAUDE.md is byte-identical to
     * what the round renders, so nothing is written — and nothing was recorded either. The next
     * build's short-circuit then asked the cache whether every file it knew about was stable, the
     * cache knew nothing about this one, and a hand edit inside the generated block survived every
     * later build while check mode reported drift on the same tree.
     */
    @Test
    void fileFoundCurrentOnFirstCachedBuild_isStillRepairedWhenEditedInsideTheBlock(@TempDir Path tmp)
            throws Exception {
        ProcessorTestHarness h = ProcessorTestHarness.withExampleSources(tmp);
        Path claude = h.root().resolve("CLAUDE.md");
        String generated = Files.readString(claude, StandardCharsets.UTF_8);
        assertTrue(generated.contains("<!-- VIBETAGS-START -->\n"), "fixture must carry a start marker");

        // A fresh clone: the generated files are committed and current, the cache is not.
        Files.delete(h.root().resolve(".vibetags-cache"));
        ProcessorTestHarness.awaitFilesystemTick(tmp);
        ProcessorTestHarness.withExampleSources(tmp);
        assertEquals(generated, Files.readString(claude, StandardCharsets.UTF_8),
            "a current file is not rewritten");

        // Somebody edits inside the generated block.
        ProcessorTestHarness.awaitFilesystemTick(tmp);
        Files.writeString(claude, generated.replace("<!-- VIBETAGS-START -->\n",
            "<!-- VIBETAGS-START -->\nHAND EDIT INSIDE THE BLOCK\n"), StandardCharsets.UTF_8);

        ProcessorTestHarness.awaitFilesystemTick(tmp);
        ProcessorTestHarness.withExampleSources(tmp);
        assertFalse(Files.readString(claude, StandardCharsets.UTF_8).contains("HAND EDIT INSIDE THE BLOCK"),
            "the next build must regenerate the block it owns, whether or not it ever wrote it before");
    }
}
