package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targets the orphan-removal branch of {@code cleanupGranularDirectory} that the existing tests
 * only exercise for crash-safety. These cases pin the behavior the GuardrailFileWriter / GranularRulesWriter
 * extraction must preserve.
 */
class CleanupGranularDirectoryTest {

    @Test
    void deletesFile_whenOnlyVibeTagsMdMarkers(@TempDir Path dir) throws IOException {
        Path orphan = dir.resolve("com-example-Foo.md");
        Files.writeString(orphan,
            "<!-- VIBETAGS-START -->\n# Rules for Foo\n- locked\n<!-- VIBETAGS-END -->\n",
            StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".md");

        assertFalse(Files.exists(orphan), "Boilerplate-only file must be deleted");
    }

    @Test
    void deletesFile_whenOnlyVibeTagsHashMarkers(@TempDir Path dir) throws IOException {
        Path orphan = dir.resolve("com-example-Bar.md");
        Files.writeString(orphan,
            "# VIBETAGS-START\nrule line\n# VIBETAGS-END\n",
            StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".md");

        assertFalse(Files.exists(orphan), "Hash-marker-only file must be deleted");
    }

    @Test
    void preservesFile_whenHumanContentSurroundsMarkers(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("com-example-Baz.md");
        String content =
            "# Hand-written rules for Baz\n\n" +
            "Some prose I wrote myself.\n\n" +
            "<!-- VIBETAGS-START -->\nold rule\n<!-- VIBETAGS-END -->\n\n" +
            "More of my prose at the bottom.\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".md");

        assertTrue(Files.exists(file), "File with human content around markers must be kept");
        String result = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(result.contains("VIBETAGS-START"), "VibeTags section must be stripped");
        assertFalse(result.contains("old rule"), "Old generated rule must be gone");
        assertTrue(result.contains("Hand-written rules for Baz"), "Top human content must survive");
        assertTrue(result.contains("Some prose I wrote myself"), "Mid human content must survive");
        assertTrue(result.contains("More of my prose at the bottom"), "Bottom human content must survive");
    }

    @Test
    void preservesFile_keepingYamlFrontMatter(@TempDir Path dir) throws IOException {
        // Cursor/Trae .mdc files have YAML front-matter that must NOT be deleted along with the markers.
        Path file = dir.resolve("com-example-Qux.mdc");
        String content =
            "---\n" +
            "description: \"AI rules for com.example.Qux\"\n" +
            "globs: [\"**/Qux.java\"]\n" +
            "alwaysApply: false\n" +
            "---\n\n" +
            "<!-- VIBETAGS-START -->\nold rule\n<!-- VIBETAGS-END -->\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".mdc");

        // After stripping markers, only the front-matter block remains. Current behavior treats
        // a body that's just front-matter as "boilerplate" and deletes the file entirely.
        // This test pins that behavior so the refactor preserves it.
        assertFalse(Files.exists(file),
            "File reduced to front-matter-only after marker stripping is treated as boilerplate and deleted");
    }

    @Test
    void respectsExcludeQNames_doesNotTouchListedFiles(@TempDir Path dir) throws IOException {
        Path keep = dir.resolve("com-example-Keep.md");
        Path remove = dir.resolve("com-example-Remove.md");
        Files.writeString(keep,
            "<!-- VIBETAGS-START -->\nfresh rule\n<!-- VIBETAGS-END -->\n",
            StandardCharsets.UTF_8);
        Files.writeString(remove,
            "<!-- VIBETAGS-START -->\nstale rule\n<!-- VIBETAGS-END -->\n",
            StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".md", Set.of("com-example-Keep"));

        assertTrue(Files.exists(keep), "File whose qName is in excludeQNames must be left alone");
        assertEquals("<!-- VIBETAGS-START -->\nfresh rule\n<!-- VIBETAGS-END -->\n",
            Files.readString(keep, StandardCharsets.UTF_8),
            "Excluded file must not even be read or rewritten");
        assertFalse(Files.exists(remove), "Non-excluded boilerplate file must be deleted");
    }

    @Test
    void ignoresFiles_thatDontMatchExtension(@TempDir Path dir) throws IOException {
        Path keep = dir.resolve("com-example-Bystander.txt");
        Files.writeString(keep,
            "<!-- VIBETAGS-START -->\nrule\n<!-- VIBETAGS-END -->\n",
            StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".md");

        assertTrue(Files.exists(keep), "Files with non-matching extension must be ignored");
    }

    @Test
    void leavesFileUntouched_whenNoMarkersPresent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("com-example-NoMarkers.md");
        String content = "# Just a plain hand-written rule file\n\nNo markers here.\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        AIGuardrailProcessor p = new AIGuardrailProcessor();
        p.cleanupGranularDirectory(dir, ".md");

        assertTrue(Files.exists(file));
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8),
            "Files without VibeTags markers must not be modified");
    }

    @Test
    void handlesEmptyDirectory(@TempDir Path dir) throws IOException {
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        assertDoesNotThrow(() -> p.cleanupGranularDirectory(dir, ".md"));
    }
}
