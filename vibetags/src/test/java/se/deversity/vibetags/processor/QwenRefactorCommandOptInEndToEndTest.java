package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
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
 * {@code .qwen/commands/refactor.md} registers a {@code /refactor} slash command in Qwen Code. It used
 * to be written implicitly whenever {@code QWEN.md} was opted in, creating {@code .qwen/commands/} in
 * projects that never asked for a command, an undocumented second exception to "file presence is the
 * only opt-in" (#655). It is now an ordinary opt-in: the file is regenerated when it exists, and
 * never created. These tests pin what a Qwen user can observe.
 */
@Tag("e2e")
class QwenRefactorCommandOptInEndToEndTest {

    private static final String REFACTOR = ".qwen/commands/refactor.md";

    private static final String LOCKED = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "Settlement contract with the bank")
        public class PaymentLedger {}
        """;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness withSource(Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.addSource("com.example.PaymentLedger", LOCKED);
        return h;
    }

    private static void write(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void qwenMdAloneCreatesNoCommandFileAndNoCommandsDirectory(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = withSource(dir);
        Files.createFile(dir.resolve("QWEN.md"));

        h.compile();

        assertTrue(h.readFile("QWEN.md").contains("PaymentLedger"), "QWEN.md itself is still generated");
        assertFalse(h.fileExists(REFACTOR),
            "a /refactor command the user never created is not VibeTags' to add");
        assertFalse(Files.exists(dir.resolve(".qwen/commands")),
            "nor is the .qwen/commands/ directory it would live in");
    }

    @Test
    void anExistingRefactorCommandKeepsRegeneratingAndHandWrittenTextSurvives(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = withSource(dir);
        String handWritten = "---\ndescription: Team refactor command\n---\n\nAlso keep public signatures stable.\n";
        write(dir, REFACTOR, handWritten);

        h.compile();
        String first = h.readFile(REFACTOR);
        h.compile();
        String second = h.readFile(REFACTOR);

        assertTrue(first.contains("# /refactor command") && first.contains("VIBETAGS-START"),
            "an existing refactor.md is an opt-in and gets the generated block, with no QWEN.md needed:\n" + first);
        assertTrue(first.contains("description: Team refactor command")
                && first.contains("Also keep public signatures stable."),
            "hand-written text outside the markers must survive:\n" + first);
        assertEquals(first, second, "regenerating is idempotent");
    }

    @Test
    void nothingIsDeleted(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = withSource(dir);
        Files.createFile(dir.resolve("QWEN.md"));
        write(dir, ".qwen/commands/test.md", "Run the tests.\n");

        h.compile();

        assertEquals("Run the tests.\n", h.readFile(".qwen/commands/test.md"),
            "a user's own command beside the one VibeTags writes is left exactly as it was");
        assertFalse(h.fileExists(REFACTOR),
            "an existing .qwen/commands/ directory is not an opt-in to refactor.md");

        Files.delete(dir.resolve("QWEN.md"));
        write(dir, REFACTOR, "");
        h.compile();
        assertTrue(h.fileExists(REFACTOR) && h.fileExists(".qwen/commands/test.md"),
            "dropping QWEN.md deletes neither command file");
    }
}
