package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A top-level YAML key the user wrote outside the VibeTags block, in a file where the block writes
 * the same key (issue #635).
 *
 * <p>The marker merge keeps the hand-authored line, as it must, and the result is a document that
 * declares the key twice. PyYAML, which aider uses, does not reject that: it keeps the last
 * occurrence, so whichever of the two sits lower in the file is read and the other is dropped with
 * nothing in any log. The marker mechanism cannot catch it, because both halves are individually
 * correct. The build can, and says so.
 */
@Tag("e2e")
class HandAuthoredYamlKeyEndToEndTest {

    private static final String LOCKED_SOURCE =
        "package com.example;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"Partner contract\")\n"
            + "public class Payments {}\n";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /**
     * The first build after opt-in is the one that creates the duplicate: the file has no block yet,
     * VibeTags appends one carrying {@code read:}, and the user's {@code read:} above it stops being
     * read. Waiting for the second build to say so would let the first one pass silently.
     */
    @Test
    void aiderConf_warnsOnTheFirstBuild_whenTheUserAlreadyDeclaresRead(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = aiderProject(root,
            "model: sonnet\n"
                + "read:\n"
                + "  - docs/STYLE.md\n");

        List<String> warnings = duplicateKeyWarnings(harness.compileReturningDiagnostics());

        assertEquals(1, warnings.size(), "exactly one warning for the one duplicated key, got: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.contains(".aider.conf.yml"), "must name the file: " + warning);
        assertTrue(warning.contains("'read'"), "must name the key: " + warning);
        assertTrue(warning.contains("line 2"), "must name the hand-authored line: " + warning);
        assertTrue(warning.contains("aider"), "must say which tool is affected: " + warning);
    }

    /**
     * Once the block is on disk both line numbers are real, and the warning has to say which one
     * aider reads. The block was appended below the user's key, so it is the block's.
     */
    @Test
    void aiderConf_namesBothLines_andSaysTheLowerOneIsTheOneAiderReads(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = aiderProject(root,
            "model: sonnet\n"
                + "read:\n"
                + "  - docs/STYLE.md\n");
        harness.compile();
        int generatedLine = lineOf(harness.readFile(".aider.conf.yml"), "read:", 2);

        List<String> warnings = duplicateKeyWarnings(harness.compileReturningDiagnostics());

        assertEquals(1, warnings.size(), "a persistent defect warns on every build, got: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.contains("line 2"), "hand-authored line: " + warning);
        assertTrue(warning.contains("line " + generatedLine), "generated line " + generatedLine + ": " + warning);
        assertTrue(warning.contains("aider reads line " + generatedLine),
            "PyYAML keeps the last occurrence, so the generated line " + generatedLine + " wins: " + warning);
    }

    /** A hand-authored key below the block is the one that wins, and the warning must not guess the other. */
    @Test
    void aiderConf_saysTheHandAuthoredLineWins_whenItSitsBelowTheBlock(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = aiderProject(root, "model: sonnet\n");
        harness.compile();
        Path conf = root.resolve(".aider.conf.yml");
        String withBlock = Files.readString(conf, StandardCharsets.UTF_8);
        Files.writeString(conf, withBlock + "\nread:\n  - docs/STYLE.md\n", StandardCharsets.UTF_8);
        String edited = Files.readString(conf, StandardCharsets.UTF_8);
        int generatedLine = lineOf(edited, "read:", 1);
        int handLine = lineOf(edited, "read:", 2);

        List<String> warnings = duplicateKeyWarnings(harness.compileReturningDiagnostics());

        assertEquals(1, warnings.size(), "got: " + warnings);
        assertTrue(warnings.get(0).contains("aider reads line " + handLine),
            "the hand-authored read: at line " + handLine + " is below the generated one at line "
                + generatedLine + " and is the one PyYAML keeps: " + warnings.get(0));
    }

    /** The common case must stay quiet, or the warning is one people learn to skip. */
    @Test
    void aiderConf_saysNothing_whenTheUserHasNoReadKeyOfTheirOwn(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = aiderProject(root,
            "model: sonnet\n"
                + "# read: is managed by VibeTags\n"
                + "lint-cmd:\n"
                + "  - \"python: flake8 --select=E9\"\n");
        harness.compile();

        assertEquals(List.of(), duplicateKeyWarnings(harness.compileReturningDiagnostics()));
    }

    /**
     * Not only aider. CodeRabbit's generated block owns {@code reviews:}, which is also where a
     * CodeRabbit user puts every review setting they have, so the same collision is the likely one
     * there, and a strict loader rejects the document outright rather than dropping half of it.
     */
    @Test
    void codeRabbit_warnsAboutAHandAuthoredReviewsKey(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve(".coderabbit.yaml"),
            "language: en-US\n"
                + "reviews:\n"
                + "  profile: assertive\n",
            StandardCharsets.UTF_8);
        harness.addSource("com.example.Payments", LOCKED_SOURCE);

        List<String> warnings = duplicateKeyWarnings(harness.compileReturningDiagnostics());

        assertEquals(1, warnings.size(), "got: " + warnings);
        assertTrue(warnings.get(0).contains(".coderabbit.yaml"), warnings.get(0));
        assertTrue(warnings.get(0).contains("'reviews'"), warnings.get(0));
    }

    /**
     * A reactor module gets its own copy of each opted-in file, so the check covers the module's
     * directory too, and names the file by its path from the root: in a reactor the bare name would
     * not say which of several {@code .aider.conf.yml} files to fix.
     */
    @Test
    void moduleDirectory_isChecked_andTheFileIsNamedByItsPathFromTheRoot(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path module = Files.createDirectories(root.resolve("app"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(module.resolve(".aider.conf.yml"), "read:\n  - docs/STYLE.md\n", StandardCharsets.UTF_8);
        harness.writeSourceFile("app/src/main/java/com/example/Payments.java", LOCKED_SOURCE);

        List<String> warnings = duplicateKeyWarnings(harness.compileReturningDiagnostics());

        assertEquals(1, warnings.size(), "got: " + warnings);
        assertTrue(warnings.get(0).contains("app/.aider.conf.yml declares"), warnings.get(0));
    }

    // -----------------------------------------------------------------------

    private static ProcessorTestHarness aiderProject(Path root, String aiderConf) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.touchOptIn("CONVENTIONS.md");
        Files.writeString(root.resolve(".aider.conf.yml"), aiderConf, StandardCharsets.UTF_8);
        harness.addSource("com.example.Payments", LOCKED_SOURCE);
        return harness;
    }

    private static List<String> duplicateKeyWarnings(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .map(d -> d.getMessage(Locale.ROOT))
            .filter(m -> m.contains("top-level key"))
            .toList();
    }

    /** 1-based line number of the {@code occurrence}-th line equal to {@code line}. */
    private static int lineOf(String content, String line, int occurrence) {
        String[] lines = content.split("\n", -1);
        int seen = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripTrailing().equals(line) && ++seen == occurrence) {
                return i + 1;
            }
        }
        throw new AssertionError("no occurrence " + occurrence + " of '" + line + "' in:\n" + content);
    }
}
