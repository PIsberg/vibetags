package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins YAML front-matter handling in {@code writeFileIfChanged}. The .mdc files Cursor uses
 * (and .md files Trae uses) ship with a YAML header that VibeTags must place markers AFTER,
 * not before. The GuardrailFileWriter extraction must keep this invariant.
 */
class WriteFileFrontMatterTest {

    @Test
    void mdcFile_freshWrite_placesMarkersAfterFrontMatter(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Foo.mdc");
        Files.createFile(file);

        String contentWithFrontMatter =
            "---\n" +
            "description: \"AI rules for com.example.Foo\"\n" +
            "globs: [\"**/Foo.java\"]\n" +
            "alwaysApply: false\n" +
            "---\n\n" +
            "# Rules for Foo\n\n" +
            "## Locked Status\n- **Reason**: legacy\n";

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        boolean changed = p.writeFileIfChanged(file.toString(), contentWithFrontMatter, true);

        assertTrue(changed);
        String result = Files.readString(file, StandardCharsets.UTF_8);

        int frontMatterStart = result.indexOf("---");
        int frontMatterEnd = result.indexOf("---", 3);
        int markerStart = result.indexOf("<!-- VIBETAGS-START -->");

        assertTrue(frontMatterStart >= 0, "Front-matter open tag must be present");
        assertTrue(frontMatterEnd > frontMatterStart, "Front-matter close tag must be present");
        assertTrue(markerStart > frontMatterEnd,
            "VIBETAGS-START marker must come AFTER the closing front-matter ---");
        assertTrue(result.contains("description: \"AI rules for com.example.Foo\""),
            "Front-matter content must be preserved verbatim");
        assertTrue(result.contains("# Rules for Foo"),
            "Body content must be preserved");
    }

    @Test
    void mdcFile_update_preservesFrontMatter(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Foo.mdc");
        // Pre-existing file with front-matter and an old VibeTags section
        String existing =
            "---\n" +
            "description: \"AI rules for com.example.Foo\"\n" +
            "globs: [\"**/Foo.java\"]\n" +
            "---\n\n" +
            "<!-- VIBETAGS-START -->\nold rules\n<!-- VIBETAGS-END -->\n";
        Files.writeString(file, existing, StandardCharsets.UTF_8);

        String newContent =
            "---\n" +
            "description: \"AI rules for com.example.Foo\"\n" +
            "globs: [\"**/Foo.java\"]\n" +
            "---\n\n" +
            "# Rules for Foo\n\n## Locked Status\n- **Reason**: refreshed\n";

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        boolean changed = p.writeFileIfChanged(file.toString(), newContent, true);

        assertTrue(changed);
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(result.contains("description: \"AI rules for com.example.Foo\""),
            "Front-matter must be preserved across updates");
        assertTrue(result.contains("globs: [\"**/Foo.java\"]"),
            "All front-matter keys must be preserved");
        assertTrue(result.contains("refreshed"), "New body content must be written");
        assertFalse(result.contains("old rules"), "Old body content must be replaced");

        int frontMatterEnd = result.indexOf("---", 3);
        int markerStart = result.indexOf("<!-- VIBETAGS-START -->");
        assertTrue(markerStart > frontMatterEnd,
            "Markers must remain after front-matter on update");
    }

    @Test
    void plainMdFile_noFrontMatter_writesMarkersFromTop(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("plain.md");
        Files.createFile(file);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.writeFileIfChanged(file.toString(), "body content", true);

        String result = Files.readString(file, StandardCharsets.UTF_8);
        // No front-matter to preserve, so markers can be at the top
        assertTrue(result.startsWith("<!-- VIBETAGS-START -->"),
            "Without front-matter, markers may start at the top");
        assertTrue(result.contains("body content"));
    }

    @Test
    void hashMarkerFile_aiderignore_appendsCorrectly(@TempDir Path tempDir) throws IOException {
        // .aiderignore uses hash-style markers (no .md extension)
        Path file = tempDir.resolve(".aiderignore");
        Files.writeString(file, "# Existing manual ignore\n*.tmp\n", StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        boolean changed = p.writeFileIfChanged(file.toString(), "**/Foo.java", true);

        assertTrue(changed);
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(result.contains("# Existing manual ignore"), "Manual content must be preserved");
        assertTrue(result.contains("*.tmp"), "Manual rules must be preserved");
        assertTrue(result.contains("# VIBETAGS-START"), "Hash markers must be used (not HTML)");
        assertTrue(result.contains("**/Foo.java"));
        assertTrue(result.contains("# VIBETAGS-END"));
        assertFalse(result.contains("<!-- VIBETAGS-START -->"),
            "Hash-marker file must not get HTML markers");
    }
}
