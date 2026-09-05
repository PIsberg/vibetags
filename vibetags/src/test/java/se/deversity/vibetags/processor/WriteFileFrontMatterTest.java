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

    /**
     * The front matter VibeTags renders is generated content, not a hand-written header: it
     * carries the {@code globs:} / {@code paths:} / {@code applyTo:} list that decides when the
     * editor loads the file at all. When the rendered list changes (a role in
     * {@code .vibetags-roles} gains a glob, an FQN-only role gains a member, a mirror adds a
     * target glob) the file's own front matter has to follow, or the rule keeps applying to the
     * old set of files while every other trace of the change says otherwise. Hand-authored lines
     * around the block stay where they are: they are the content the markers exist to protect.
     */
    @Test
    void mdcFile_update_refreshesRenderedFrontMatter_andKeepsHandContentAroundTheBlock(
            @TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("web.mdc");
        String existing =
            "---\n" +
            "description: \"AI rules for role web\"\n" +
            "globs: [\"**/*Controller.java\"]\n" +
            "alwaysApply: false\n" +
            "---\n\n" +
            "Hand note between the header and the block.\n\n" +
            "<!-- VIBETAGS-START -->\nold rules\n<!-- VIBETAGS-END -->\n\n" +
            "Hand note after the block.\n";
        Files.writeString(file, existing, StandardCharsets.UTF_8);

        String rendered =
            "---\n" +
            "description: \"AI rules for role web\"\n" +
            "globs: [\"**/*Controller.java\", \"**/*Endpoint.java\"]\n" +
            "alwaysApply: false\n" +
            "---\n\n" +
            "# Rules for web\n\n## Locked Status\n- **Reason**: refreshed\n";

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        assertTrue(p.writeFileIfChanged(file.toString(), rendered, true),
            "a changed front matter is a change to the file");
        String result = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(result.startsWith("---\ndescription: \"AI rules for role web\"\n"
                + "globs: [\"**/*Controller.java\", \"**/*Endpoint.java\"]\nalwaysApply: false\n---\n"),
            "the rendered front matter must replace the stale one, at the top of the file:\n" + result);
        assertFalse(result.contains("globs: [\"**/*Controller.java\"]\n"),
            "the stale glob list must be gone:\n" + result);
        int frontMatterEnd = result.indexOf("---", 3);
        int handBefore = result.indexOf("Hand note between the header and the block.");
        int markerStart = result.indexOf("<!-- VIBETAGS-START -->");
        int markerEnd = result.indexOf("<!-- VIBETAGS-END -->");
        int handAfter = result.indexOf("Hand note after the block.");
        assertTrue(frontMatterEnd < handBefore && handBefore < markerStart,
            "hand content between the front matter and the block must stay there:\n" + result);
        assertTrue(markerEnd < handAfter, "hand content after the block must survive:\n" + result);
        assertTrue(result.contains("refreshed") && !result.contains("old rules"),
            "the block itself must be refreshed as before:\n" + result);
    }

    /**
     * The other half of the rule. A file whose renderer emits no front matter — CLAUDE.md, a
     * Junie or Void rules file — may carry a hand-written YAML header, and that header is
     * hand-authored content outside the markers: it must be preserved exactly as before.
     */
    @Test
    void mdFile_update_keepsHandFrontMatter_whenRenderedContentHasNone(@TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("CLAUDE.md");
        String existing =
            "---\n" +
            "title: My project notes\n" +
            "---\n\n" +
            "<!-- VIBETAGS-START -->\nold rules\n<!-- VIBETAGS-END -->\n";
        Files.writeString(file, existing, StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        assertTrue(p.writeFileIfChanged(file.toString(), "# Rules\n- refreshed\n", true));
        String result = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(result.startsWith("---\ntitle: My project notes\n---\n"),
            "a hand-written header the renderer knows nothing about must survive:\n" + result);
        assertTrue(result.contains("refreshed") && !result.contains("old rules"));
    }

    /**
     * A hand-written rule file with no YAML header — a role file somebody created ahead of the
     * build, say — must still end up with the header VibeTags renders for it. The header is where
     * Cursor, Windsurf and Copilot read the glob list that decides when the rule loads at all;
     * appending the block beneath the hand text without it produced a rule no editor ever applied,
     * and the only trace was the absence of four lines nobody had seen before.
     */
    @Test
    void mdcFile_appendToHandFileWithoutHeader_addsTheRenderedFrontMatter(@TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("security.mdc");
        Files.writeString(file, "# Team notes\n\nKeep it small.\n", StandardCharsets.UTF_8);
        String rendered =
            "---\n" +
            "description: \"AI rules for role security\"\n" +
            "globs: [\"**/*Auth*.java\"]\n" +
            "alwaysApply: false\n" +
            "---\n\n" +
            "# Rules for security\n\n## Locked Status\n- **Reason**: audited\n";
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        assertTrue(p.writeFileIfChanged(file.toString(), rendered, true));
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(result.startsWith("---\ndescription: \"AI rules for role security\"\n"
                + "globs: [\"**/*Auth*.java\"]\nalwaysApply: false\n---\n"),
            "the rendered front matter must open the file:\n" + result);
        int frontMatterEnd = result.indexOf("---", 3);
        int hand = result.indexOf("# Team notes");
        int marker = result.indexOf("<!-- VIBETAGS-START -->");
        assertTrue(frontMatterEnd < hand && hand < marker,
            "hand content stays between the header and the block:\n" + result);
        assertTrue(result.contains("Keep it small."), "hand content survives:\n" + result);
        assertFalse(p.writeFileIfChanged(file.toString(), rendered, true),
            "the second round must find the file current");
    }

    /**
     * The same header, for a file that opens directly on the marker block. That is what the
     * append above left behind before it was fixed, so an already-affected file has to heal on
     * its next round rather than stay headerless forever.
     */
    @Test
    void mdcFile_updateOfHeaderlessMarkerFile_addsTheRenderedFrontMatter(@TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("security.mdc");
        Files.writeString(file, "<!-- VIBETAGS-START -->\nold rules\n<!-- VIBETAGS-END -->\n",
            StandardCharsets.UTF_8);
        String rendered =
            "---\n" +
            "globs: [\"**/*Auth*.java\"]\n" +
            "---\n\n" +
            "# Rules for security\n\n## Locked Status\n- **Reason**: audited\n";
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        assertTrue(p.writeFileIfChanged(file.toString(), rendered, true));
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(result.startsWith("---\nglobs: [\"**/*Auth*.java\"]\n---\n\n<!-- VIBETAGS-START -->\n"),
            "the rendered front matter must open the file:\n" + result);
        assertFalse(result.contains("old rules"), result);
    }
}
