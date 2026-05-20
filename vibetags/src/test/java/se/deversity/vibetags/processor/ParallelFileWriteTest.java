package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the parallel file-write phase (ForkJoinPool.commonPool()) produces correct,
 * non-corrupted output when all platform signal files are present (50+ active services).
 *
 * <p>This test exercises the parallel write path end-to-end: ProcessorTestHarness touches every
 * opt-in file, so the parallel stream has the maximum number of entries and the thread-safety
 * of WriteCache (synchronized) and SynchronizedMessager are fully exercised.
 */
class ParallelFileWriteTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = ProcessorTestHarness.withExampleSources(tempDir);
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void parallelWrites_cursorRulesIsNonEmpty() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("VIBETAGS-START"), ".cursorrules must contain VIBETAGS-START — parallel write must not corrupt output");
        assertTrue(content.contains("com.example.payment.PaymentProcessor"), ".cursorrules must list @AILocked entry after parallel write");
    }

    @Test
    void parallelWrites_claudeMdIsNonEmpty() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("VIBETAGS-START"), "CLAUDE.md must contain VIBETAGS-START — parallel write must not corrupt output");
        assertTrue(content.contains("locked_files"), "CLAUDE.md must contain locked_files XML section after parallel write");
    }

    @Test
    void parallelWrites_agentsMdIsNonEmpty() throws IOException {
        String content = harness.readFile("AGENTS.md");
        assertTrue(content.contains("VIBETAGS-START"), "AGENTS.md must be written correctly by parallel writer");
    }

    @Test
    void parallelWrites_llmsTxtIsNonEmpty() throws IOException {
        String content = harness.readFile("llms.txt");
        assertTrue(content.contains("VIBETAGS-START"), "llms.txt must be written correctly by parallel writer");
    }

    @Test
    void parallelWrites_clinerulesMirrorsContent() throws IOException {
        String cline = harness.readFile(".clinerules");
        String cursor = harness.readFile(".cursorrules");
        // Both are written in parallel — they should both contain the same locked entry
        assertTrue(cline.contains("com.example.payment.PaymentProcessor"),
            ".clinerules must not be corrupted or empty after parallel write");
        assertTrue(cursor.contains("com.example.payment.PaymentProcessor"),
            ".cursorrules must not be corrupted or empty after parallel write");
    }

    @Test
    void parallelWrites_allMajorFilesPresent() throws IOException {
        assertTrue(harness.fileExists(".cursorrules"), ".cursorrules must exist after parallel write");
        assertTrue(harness.fileExists("CLAUDE.md"), "CLAUDE.md must exist after parallel write");
        assertTrue(harness.fileExists("AGENTS.md"), "AGENTS.md must exist after parallel write");
        assertTrue(harness.fileExists("QWEN.md"), "QWEN.md must exist after parallel write");
        assertTrue(harness.fileExists("GEMINI.md"), "GEMINI.md must exist after parallel write");
        assertTrue(harness.fileExists(".clinerules"), ".clinerules must exist after parallel write");
        assertTrue(harness.fileExists(".junie/guidelines.md"), ".junie/guidelines.md must exist after parallel write");
    }
}
