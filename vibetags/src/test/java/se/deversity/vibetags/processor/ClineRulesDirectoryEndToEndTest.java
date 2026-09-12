package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cline's {@code .clinerules/} directory form (issue #642).
 *
 * <p>The front matter is taken from Cline's source, not its prose: {@code rule-helpers.ts} parses
 * each rule file's YAML front matter and evaluates a {@code paths:} list of globs against the files
 * in the task's context, which includes files Cline is about to edit. That is the same activation
 * model as Claude Code's {@code paths:}, so an edit to a locked class pulls its rule in before the
 * edit rather than after. A rule with no front matter would instead load on every request, for
 * every annotated class in the project.
 */
@Tag("e2e")
class ClineRulesDirectoryEndToEndTest {

    private static final String LOCKED_RULE = ".clinerules/com-example-payment-PaymentProcessor.md";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness withLockedClass(Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.addSource("com.example.payment.PaymentProcessor",
            "package com.example.payment;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"Core payment logic - do not refactor\")\n"
                + "public class PaymentProcessor {}\n");
        return h;
    }

    @Test
    void aDirectoryOptInGetsOneScopedRuleFilePerElement(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".clinerules"));
        ProcessorTestHarness h = withLockedClass(root);

        h.compile();

        assertTrue(Files.isDirectory(root.resolve(".clinerules")),
            ".clinerules must still be a directory after the build");
        String rule = h.readFile(LOCKED_RULE);
        assertTrue(rule.startsWith("---\npaths: [\"**/PaymentProcessor.java\"]\n---\n"),
            "Cline evaluates a paths: glob list, so the rule must carry one to load when the class "
                + "is in context. Was:\n" + rule);
        assertTrue(rule.contains("Core payment logic - do not refactor"),
            "the rule file must carry the element's guardrail. Was:\n" + rule);
    }

    /** Cline reads every {@code .md} in the directory; the ones VibeTags did not write are the user's. */
    @Test
    void aHandWrittenRuleInTheDirectoryIsLeftByteIdentical(@TempDir Path root) throws IOException {
        Path handWritten = root.resolve(".clinerules/coding.md");
        Files.createDirectories(handWritten.getParent());
        String mine = "# Coding standards\n\n- Use records for value types\n";
        Files.writeString(handWritten, mine, StandardCharsets.UTF_8);
        ProcessorTestHarness h = withLockedClass(root);

        h.compile();

        assertEquals(mine, Files.readString(handWritten, StandardCharsets.UTF_8),
            "a rule file with no VibeTags markers is not VibeTags' to rewrite or sweep");
        assertTrue(h.fileExists(LOCKED_RULE), "the generated rule must still be written beside it");
    }

    /**
     * The migration Cline performs itself. Creating a workspace rule from Cline's UI while a
     * {@code .clinerules} file exists runs {@code ensureLocalClineDirExists}, which turns the file
     * into a directory and moves its content, VibeTags block included, into
     * {@code .clinerules/default-rules.md}. The next build sees a directory, so the file service is
     * off and the directory service is on. Nothing the user wrote may be lost across that switch, and
     * the aggregate block Cline carried along must not stay behind as a stale second copy of every
     * guardrail.
     */
    @Test
    void clinesOwnFileToDirectoryConversionKeepsHandTextAndDropsTheStaleBlock(@TempDir Path root)
            throws IOException {
        Path clinerules = root.resolve(".clinerules");
        Files.writeString(clinerules, "# Team notes\n\nAlways run the linter.\n", StandardCharsets.UTF_8);
        ProcessorTestHarness h = withLockedClass(root);
        h.compile();
        String asFile = Files.readString(clinerules, StandardCharsets.UTF_8);
        assertTrue(asFile.contains("VIBETAGS-START") && asFile.contains("Always run the linter."),
            "precondition: the single-file service wrote its block around the hand text. Was:\n" + asFile);
        VibeTagsLogger.shutdown();

        // What Cline's ensureLocalClineDirExists does, step for step.
        Path backup = root.resolve(".clinerules.bak");
        Files.move(clinerules, backup);
        Files.createDirectories(clinerules);
        Files.writeString(clinerules.resolve("default-rules.md"), asFile, StandardCharsets.UTF_8);
        Files.delete(backup);

        h.compile();

        String converted = Files.readString(clinerules.resolve("default-rules.md"), StandardCharsets.UTF_8);
        assertTrue(converted.contains("Always run the linter."),
            "the user's hand text must survive the switch from file to directory. Was:\n" + converted);
        assertFalse(converted.contains("VIBETAGS-START"),
            "the aggregate block Cline moved into default-rules.md is now a stale duplicate of the "
                + "per-element rules and must be swept. Was:\n" + converted);
        assertTrue(h.fileExists(LOCKED_RULE),
            "the directory service must take over and write the per-element rule");
    }
}
