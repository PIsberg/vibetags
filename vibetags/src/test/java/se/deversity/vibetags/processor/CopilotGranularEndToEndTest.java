package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the GitHub Copilot granular platform (.github/instructions/*.instructions.md).
 *
 * Verifies that when .github/instructions/ exists on disk, the processor writes per-class
 * instruction files named *.instructions.md with an `applyTo` YAML frontmatter glob
 * (Copilot's path-scoping field).
 */
class CopilotGranularEndToEndTest {

    private static final String LOCKED_FILE = ".github/instructions/com-example-payment-PaymentProcessor.instructions.md";

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
    void copilotGranular_lockedClassFileIsGenerated() {
        assertTrue(harness.fileExists(LOCKED_FILE), ".github/instructions/ must contain a *.instructions.md file for @AILocked class");
    }

    @Test
    void copilotGranular_auditClassFileIsGenerated() {
        assertTrue(harness.fileExists(".github/instructions/com-example-database-DatabaseConnector.instructions.md"),
            ".github/instructions/ must contain a *.instructions.md file for @AIAudit class");
    }

    @Test
    void copilotGranular_fileContainsApplyToFrontMatter() throws IOException {
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.startsWith("---\napplyTo: \""), "Copilot instructions file must start with an 'applyTo:' YAML frontmatter glob");
    }

    @Test
    void copilotGranular_fileContainsInstructionsHeading() throws IOException {
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.contains("# Copilot Instructions for PaymentProcessor"),
            "Copilot instructions file must contain a 'Copilot Instructions for' heading");
    }

    @Test
    void copilotGranular_fileContainsVibeTagsMarkers() throws IOException {
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.contains("VIBETAGS-START"), "Copilot instructions file must contain VIBETAGS-START marker");
    }

    @Test
    void copilotGranular_survivesCleanupAcrossRecompiles() throws IOException {
        // Regression test for the cleanupGranularDirectory() qName-stripping bug: a double-dot
        // extension (".instructions.md") must not be mistaken for an orphan on the very next compile.
        assertTrue(harness.fileExists(LOCKED_FILE), "file must still exist after the harness's compile pass");
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.contains("Locked Status"),
            "file content must survive cleanup, not be scrubbed as orphaned");
    }
}
