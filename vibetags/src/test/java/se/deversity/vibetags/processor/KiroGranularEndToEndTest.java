package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Amazon Kiro granular platform (.kiro/steering/).
 *
 * Verifies that when .kiro/steering/ exists on disk, the processor writes per-class
 * steering files in plain Markdown (no YAML front-matter) with Kiro-specific headings.
 */
class KiroGranularEndToEndTest {

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
    void kiroGranular_lockedClassFileIsGenerated() {
        assertTrue(harness.fileExists(".kiro/steering/com-example-payment-PaymentProcessor.md"),
            ".kiro/steering/ must contain a file for @AILocked class");
    }

    @Test
    void kiroGranular_auditClassFileIsGenerated() {
        assertTrue(harness.fileExists(".kiro/steering/com-example-database-DatabaseConnector.md"),
            ".kiro/steering/ must contain a file for @AIAudit class");
    }

    @Test
    void kiroGranular_fileContainsKiroHeading() throws IOException {
        String content = harness.readFile(".kiro/steering/com-example-payment-PaymentProcessor.md");
        assertTrue(content.contains("Amazon Kiro Steering"),
            "Kiro steering file must start with 'Amazon Kiro Steering' heading");
    }

    @Test
    void kiroGranular_fileContainsLockedContent() throws IOException {
        String content = harness.readFile(".kiro/steering/com-example-payment-PaymentProcessor.md");
        assertTrue(content.contains("Locked Status") || content.contains("LOCKED") || content.contains("must not be modified"),
            "Kiro steering file must contain locked status information");
    }

    @Test
    void kiroGranular_fileHasNoYamlFrontMatter() throws IOException {
        String content = harness.readFile(".kiro/steering/com-example-payment-PaymentProcessor.md");
        assertFalse(content.startsWith("---"),
            "Kiro steering files must not have YAML front-matter (plain Markdown only)");
    }

    @Test
    void kiroGranular_fileContainsVibeTagsMarkers() throws IOException {
        String content = harness.readFile(".kiro/steering/com-example-payment-PaymentProcessor.md");
        assertTrue(content.contains("VIBETAGS-START"),
            "Kiro steering file must contain VIBETAGS-START marker");
    }
}
