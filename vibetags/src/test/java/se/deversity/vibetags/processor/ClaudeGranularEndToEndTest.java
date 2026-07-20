package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Claude Code granular platform (.claude/rules/*.md).
 *
 * Verifies that when .claude/rules/ exists on disk, the processor writes per-class
 * rule files with a `paths:` YAML frontmatter glob (Claude Code's path-scoping field).
 */
class ClaudeGranularEndToEndTest {

    private static final String LOCKED_FILE = ".claude/rules/com-example-payment-PaymentProcessor.md";

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = ProcessorTestHarness.withExampleSources(tempDir, ".claude/rules/.vibetags");
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void claudeGranular_lockedClassFileIsGenerated() {
        assertTrue(harness.fileExists(LOCKED_FILE), ".claude/rules/ must contain a file for @AILocked class");
    }

    @Test
    void claudeGranular_auditClassFileIsGenerated() {
        assertTrue(harness.fileExists(".claude/rules/com-example-database-DatabaseConnector.md"),
            ".claude/rules/ must contain a file for @AIAudit class");
    }

    @Test
    void claudeGranular_fileContainsPathsFrontMatter() throws IOException {
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.startsWith("---\npaths: ["), "Claude granular rule file must start with a 'paths:' YAML frontmatter list");
    }

    @Test
    void claudeGranular_fileContainsRulesHeading() throws IOException {
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.contains("# Rules for PaymentProcessor"), "Claude granular rule file must contain a 'Rules for' heading");
    }

    @Test
    void claudeGranular_fileContainsVibeTagsMarkers() throws IOException {
        String content = harness.readFile(LOCKED_FILE);
        assertTrue(content.contains("VIBETAGS-START"), "Claude granular rule file must contain VIBETAGS-START marker");
    }
}
