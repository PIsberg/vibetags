package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targets missed branch coverage in GuardrailFileWriter:
 *
 *   L118 false  - writeCache==null + byte-equal content in streaming path → no write
 *   L166 false  - content starts with "---" but no closing "---" → secondTriple==-1
 *   L198 true   - markers present + content differs + hasNewRules=false → skip
 *   L211 true   - legacy VibeTags file (no markers, has header) + hasNewRules=false → skip
 *   L226 true   - non-VibeTags file with human content + hasNewRules=false → skip
 *   L243 true   - JSON file + size mismatch + hasNewRules=false → skip in writeWithoutMarkers
 *   L324 true   - cleanupGranularDirectory with null dir → no-op
 *   L324 true   - cleanupGranularDirectory with non-existent dir → no-op
 *   L331 false  - filename without dot → dot<0 → use full name as key
 */
class GuardrailFileWriterEdgeCaseTest {

    // ------------------------------------------------------------------
    // L118 false branch: null cache + streaming path + byte-equal → no write
    // ------------------------------------------------------------------

    /**
     * Non-marker file where the on-disk size equals the new content size exactly and
     * the bytes are identical. The streaming compare returns true (byte-equal), so
     * writeFileIfChanged must return false WITHOUT a write cache being present.
     * This covers the "writeCache != null" false branch at the cache-refresh call.
     */
    @Test
    void streaming_nullCache_byteEqual_returnsNoWrite(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path file = tmp.resolve("config.json");
        String body = "identical-content\n";
        Files.writeString(file, body);
        assertFalse(writer.writeFileIfChanged(file.toString(), body, true),
            "byte-equal content with null cache must not trigger write");
    }

    // ------------------------------------------------------------------
    // L123 false branch: non-marker file, same-size diff-bytes, hasNewRules=true → write
    // ------------------------------------------------------------------

    /**
     * Non-marker JSON file where the on-disk byte count equals the new content byte count,
     * but the bytes differ. With hasNewRules=true the "sizes match, bytes differ" path falls
     * through to writeAndCache — it must NOT skip the write. Covers the
     * {@code if (!hasNewRules && existingSize > 0)} false branch at L123.
     */
    @Test
    void nonMarkerFile_sameSizeDiffBytes_hasNewRulesTrue_writes(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path file = tmp.resolve("config.json");
        // Both strings are 14 UTF-8 bytes — same size, different content.
        String existing = "{\"v\": \"old\"}\n";
        String newContent = "{\"v\": \"new\"}\n";
        Files.writeString(file, existing);
        assertTrue(writer.writeFileIfChanged(file.toString(), newContent, true),
            "same-size different-bytes JSON file with hasNewRules=true must be written");
        assertEquals(newContent, Files.readString(file));
    }

    // ------------------------------------------------------------------
    // L166 false branch: front-matter starts with --- but no closing ---
    // ------------------------------------------------------------------

    /**
     * The content starts with "---" but there is no second "---" terminator.
     * secondTriple == -1 → the if-block is skipped (false branch), body == content.
     * The file must still be written (markers appended).
     */
    @Test
    void frontMatter_noClosingTripleDash_treatedAsPlainBody(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path file = tmp.resolve("rules.md");
        Files.createFile(file);
        String content = "---\nno closing triple dash\nbody text here\n";
        assertTrue(writer.writeFileIfChanged(file.toString(), content, true));
        assertTrue(Files.readString(file).contains("body text here"));
    }

    // ------------------------------------------------------------------
    // L198 true branch: markers present + different content + hasNewRules=false → skip
    // ------------------------------------------------------------------

    /**
     * Existing file has VIBETAGS markers. New content is different. hasNewRules=false
     * → writeWithMarkers detects the difference but skips the update (skip path at L198).
     */
    @Test
    void markersPresent_hasNewRulesFalse_skipsUpdate(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path file = tmp.resolve("CLAUDE.md");
        String existingContent = "<!-- VIBETAGS-START -->\nold rules\n<!-- VIBETAGS-END -->\n";
        Files.writeString(file, existingContent);
        assertFalse(writer.writeFileIfChanged(file.toString(), "new different content", false),
            "hasNewRules=false with existing markers must skip update");
        assertEquals(existingContent, Files.readString(file));
    }

    // ------------------------------------------------------------------
    // L211 true branch: legacy VibeTags file + hasNewRules=false → skip
    // ------------------------------------------------------------------

    /**
     * Existing file contains the VibeTags header but no markers (legacy format).
     * hasNewRules=false → the legacy upgrade path skips the write (L211 true branch).
     */
    @Test
    void legacyVibeTagsFile_hasNewRulesFalse_skipsUpdate(@TempDir Path tmp) throws IOException {
        String header = "# VibeTags\n";
        GuardrailFileWriter writer = new GuardrailFileWriter(header, null, null, null);
        Path file = tmp.resolve("CLAUDE.md");
        // Legacy file: contains the VibeTags header but NO VIBETAGS-START/END markers.
        String legacyContent = "# VibeTags\n\n## Old Rules\nsome content\n";
        Files.writeString(file, legacyContent);
        assertFalse(writer.writeFileIfChanged(file.toString(), "new content", false),
            "legacy VibeTags file + hasNewRules=false must skip update");
        assertEquals(legacyContent, Files.readString(file));
    }

    // ------------------------------------------------------------------
    // L226 true branch: non-VibeTags file with human content + hasNewRules=false → skip
    // ------------------------------------------------------------------

    /**
     * Existing file is a plain human-authored Markdown file (no VibeTags header, no markers).
     * hasNewRules=false → the "append to non-VibeTags file" path skips the update (L226).
     */
    @Test
    void humanFile_hasNewRulesFalse_skipsUpdate(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path file = tmp.resolve("AGENTS.md");
        String humanContent = "# Manual Rules\nDo not change this.\n";
        Files.writeString(file, humanContent);
        assertFalse(writer.writeFileIfChanged(file.toString(), "generated rules", false),
            "non-VibeTags file with human content + hasNewRules=false must skip update");
        assertEquals(humanContent, Files.readString(file));
    }

    // ------------------------------------------------------------------
    // L243 true branch: JSON file + size mismatch + hasNewRules=false → skip
    // ------------------------------------------------------------------

    /**
     * JSON file (no markers). New content is larger than existing by more than 64 bytes
     * (nonMarkerSizeMismatch=true → existing is read as null → writeWithoutMarkers is
     * called with existing="" but fileExists=true and existingSize>0). hasNewRules=false
     * → skip path at L243.
     */
    @Test
    void jsonFile_sizeMismatch_hasNewRulesFalse_skipsUpdate(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path file = tmp.resolve("config.json");
        String existing = "{\"old\": true}\n";
        Files.writeString(file, existing);
        // New content is significantly larger to trigger nonMarkerSizeMismatch (diff > 64 bytes)
        String newContent = "{\"new\": true, \"extra\": \"" + "x".repeat(100) + "\"}\n";
        assertFalse(writer.writeFileIfChanged(file.toString(), newContent, false),
            "JSON file + hasNewRules=false must skip update");
        assertEquals(existing, Files.readString(file));
    }

    // ------------------------------------------------------------------
    // L324 true branches: cleanupGranularDirectory null/non-existent guard
    // ------------------------------------------------------------------

    /** null dir → the guard at L324 returns immediately without throwing. */
    @Test
    void cleanupGranularDirectory_nullDir_doesNotThrow() {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        assertDoesNotThrow(() -> writer.cleanupGranularDirectory(null, ".mdc"));
    }

    /** Non-existent dir → the guard at L324 returns immediately without throwing. */
    @Test
    void cleanupGranularDirectory_nonExistentDir_doesNotThrow(@TempDir Path tmp) {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path nonExistent = tmp.resolve("no-such-dir");
        assertDoesNotThrow(() -> writer.cleanupGranularDirectory(nonExistent, ".mdc"));
    }

    // ------------------------------------------------------------------
    // L331 false branch: filename without a dot → dot < 0 → use name as key
    // ------------------------------------------------------------------

    /**
     * A granular rule file with no extension in its filename triggers the dot<0 branch
     * inside cleanupGranularDirectory's filter lambda. With extension="" every file
     * passes the endsWith filter. The file contains HASH markers; after scrubbing it
     * becomes empty and is deleted. No exception must be thrown.
     */
    @Test
    void cleanupGranularDirectory_fileWithoutExtension_isProcessed(@TempDir Path tmp)
            throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path rulesDir = tmp.resolve("rules");
        Files.createDirectories(rulesDir);
        // File has no dot in its name; contains VIBETAGS hash markers.
        Path noExtFile = rulesDir.resolve("no-extension-file");
        Files.writeString(noExtFile,
            "# VIBETAGS-START\nsome rules\n# VIBETAGS-END\n");
        // extension="" → all files pass filter; the dot<0 branch is exercised.
        assertDoesNotThrow(() -> writer.cleanupGranularDirectory(rulesDir, "", Set.of()));
    }

    // ------------------------------------------------------------------
    // L375 false branch: content starts with "---" but no closing "---" after marker removal
    // ------------------------------------------------------------------

    /**
     * After removing HASH markers the residual content starts with "---" but has no
     * second "---" terminator. This exercises the {@code secondTriple == -1} false branch
     * at L375 in scrubGranularFile — body is treated as the full content (no front-matter
     * to strip), and the residual is written to disk unchanged.
     */
    @Test
    void scrubGranularFile_residualStartsWithDashNoClose_writesFullContent(@TempDir Path tmp)
            throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path rulesDir = tmp.resolve("rules");
        Files.createDirectories(rulesDir);
        // File content: front-matter-like prefix (no closing ---) followed by HASH markers.
        // After marker removal the residual is "---\nno closing triple dash\n", which starts
        // with "---" but has no second "---" → secondTriple == -1.
        Path ruleFile = rulesDir.resolve("com-example-Rule.cursorrules");
        Files.writeString(ruleFile,
            "---\nno closing triple dash\n"
                + "# VIBETAGS-START\ngenerated rule\n# VIBETAGS-END\n");

        writer.cleanupGranularDirectory(rulesDir, ".cursorrules", Set.of());

        assertTrue(Files.exists(ruleFile), "file with human front-matter prefix must not be deleted");
        String remaining = Files.readString(ruleFile);
        assertFalse(remaining.contains("VIBETAGS-START"), "generated section must be removed");
        assertTrue(remaining.contains("no closing triple dash"), "residual content must be preserved");
    }

    /**
     * scrubGranularFile: after removing HASH markers, the remaining content is non-empty
     * and does NOT start with "---". Covers the {@code content.startsWith("---")} false
     * branch at L373 in scrubGranularFile — the isEmptyOrBoilerplate=false path where no
     * YAML front-matter check is needed.
     */
    @Test
    void scrubGranularFile_hashMarkersWithHumanPrefix_writesResidualContent(@TempDir Path tmp)
            throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path rulesDir = tmp.resolve("rules");
        Files.createDirectories(rulesDir);
        // Human content BEFORE hash markers; content after removal will not start with "---"
        Path ruleFile = rulesDir.resolve("com-example-Rule.cursorrules");
        Files.writeString(ruleFile,
            "human notes here\n"
                + "# VIBETAGS-START\ngenerated rule\n# VIBETAGS-END\n");

        writer.cleanupGranularDirectory(rulesDir, ".cursorrules", Set.of());

        // The VibeTags section is removed but "human notes here" survives
        assertTrue(Files.exists(ruleFile), "file with human content must not be deleted");
        String remaining = Files.readString(ruleFile);
        assertTrue(remaining.contains("human notes here"), "human prefix must be preserved");
        assertFalse(remaining.contains("VIBETAGS-START"), "generated section must be removed");
    }

    // ------------------------------------------------------------------
    // Security: the staging file must NOT use the predictable "<file>.vibetags-tmp"
    // path (a pre-planted symlink there could otherwise redirect the write). We use a
    // secure random temp instead, so a file sitting at that predictable path is untouched.
    // ------------------------------------------------------------------

    @Test
    void doesNotWriteToPredictableTempPath(@TempDir Path tmp) throws IOException {
        GuardrailFileWriter writer = new GuardrailFileWriter("# VibeTags\n", null, null, null);
        Path target = tmp.resolve("CLAUDE.md");
        Path predictableTmp = tmp.resolve("CLAUDE.md.vibetags-tmp");
        String sentinel = "DO-NOT-CLOBBER\n";
        Files.writeString(predictableTmp, sentinel);

        boolean wrote = writer.writeFileIfChanged(target.toString(), "new rules\n", true);

        assertTrue(wrote, "target should be written");
        assertTrue(Files.readString(target).contains("new rules"), "target must have the new content");
        assertEquals(sentinel, Files.readString(predictableTmp),
            "writer must not use the predictable <file>.vibetags-tmp path (symlink-clobber guard)");
    }
}
