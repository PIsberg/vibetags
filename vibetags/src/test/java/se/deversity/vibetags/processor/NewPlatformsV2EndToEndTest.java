package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for platforms added in v0.8.0:
 * PearAI (granular), Mentat, Sweep, Plandex, Double.bot, Open Interpreter, and Codeium.
 */
class NewPlatformsV2EndToEndTest {

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

    // -----------------------------------------------------------------------
    // File existence
    // -----------------------------------------------------------------------

    @Test
    void testFlatFilesExist() {
        assertTrue(harness.fileExists(".mentatconfig.json"), ".mentatconfig.json should exist");
        assertTrue(harness.fileExists("sweep.yaml"), "sweep.yaml should exist");
        assertTrue(harness.fileExists(".plandex.yaml"), ".plandex.yaml should exist");
        assertTrue(harness.fileExists(".doubleignore"), ".doubleignore should exist");
        assertTrue(harness.fileExists(".interpreter/profiles/vibetags.yaml"),
            ".interpreter/profiles/vibetags.yaml should exist");
        assertTrue(harness.fileExists(".codeiumignore"), ".codeiumignore should exist");
    }

    @Test
    void testPearAiGranularDirectoryExists() {
        assertTrue(harness.fileExists(".pearai/rules/com-example-payment-PaymentProcessor.md"),
            ".pearai/rules should have PaymentProcessor rule");
    }

    // -----------------------------------------------------------------------
    // PearAI granular .pearai/rules/*.md
    // -----------------------------------------------------------------------

    @Test
    void testPearAiGranularRulesHaveYamlFrontMatter() throws IOException {
        String content = harness.readFile(".pearai/rules/com-example-payment-PaymentProcessor.md");

        assertTrue(content.startsWith("---"), "Should start with YAML front-matter");
        assertTrue(content.contains("description: \"AI rules for com.example.payment.PaymentProcessor\""),
            "Should have description in front-matter");
        assertTrue(content.contains("globs: [\"**/PaymentProcessor.java\"]"),
            "Should have globs in front-matter");
        assertTrue(content.contains("alwaysApply: false"), "Should have alwaysApply field");
    }

    @Test
    void testPearAiGranularRulesHaveContent() throws IOException {
        String content = harness.readFile(".pearai/rules/com-example-payment-PaymentProcessor.md");

        assertTrue(content.contains("# Rules for PaymentProcessor"), "Should have rules heading");
        assertTrue(content.contains("Locked Status"), "Should have locked status section");
        assertTrue(content.contains("VIBETAGS-START"), "Should have VibeTags markers");
    }

    // -----------------------------------------------------------------------
    // Mentat .mentatconfig.json
    // -----------------------------------------------------------------------

    @Test
    void testMentatConfigIsValidJsonStructure() throws IOException {
        String content = harness.readFile(".mentatconfig.json");

        assertTrue(content.startsWith("{"), "Should start with {");
        assertTrue(content.trim().endsWith("}"), "Should end with }");
        assertTrue(content.contains("\"_generated_by\": \"VibeTags\""), "Should have generated_by field");
        assertTrue(content.contains("\"rules\""), "Should have rules object");
    }

    @Test
    void testMentatConfigHasLockedFiles() throws IOException {
        String content = harness.readFile(".mentatconfig.json");

        assertTrue(content.contains("\"locked_files\""), "Should have locked_files section");
        assertTrue(content.contains("PaymentProcessor"), "Should mention @AILocked PaymentProcessor");
    }

    @Test
    void testMentatConfigHasAuditSection() throws IOException {
        String content = harness.readFile(".mentatconfig.json");

        assertTrue(content.contains("\"audit\""), "Should have audit section");
        assertTrue(content.contains("DatabaseConnector"), "Should mention @AIAudit DatabaseConnector");
        assertTrue(content.contains("SQL Injection"), "Should include SQL Injection check");
    }

    @Test
    void testMentatConfigHasPrivacySection() throws IOException {
        String content = harness.readFile(".mentatconfig.json");

        assertTrue(content.contains("\"privacy\""), "Should have privacy section");
    }

    @Test
    void testMentatConfigHasIgnoredSection() throws IOException {
        String content = harness.readFile(".mentatconfig.json");

        assertTrue(content.contains("\"ignored\""), "Should have ignored section");
        assertTrue(content.contains("GeneratedMetadata"), "Should mention @AIIgnore GeneratedMetadata");
    }

    // -----------------------------------------------------------------------
    // Sweep sweep.yaml
    // -----------------------------------------------------------------------

    @Test
    void testSweepYamlHasCorrectStructure() throws IOException {
        String content = harness.readFile("sweep.yaml");

        assertTrue(content.contains("AUTO-GENERATED BY VIBETAGS"), "Should have auto-generated header");
        assertTrue(content.contains("rules:"), "Should have rules: key");
    }

    @Test
    void testSweepYamlHasLockedFilesRule() throws IOException {
        String content = harness.readFile("sweep.yaml");

        assertTrue(content.contains("PaymentProcessor"), "Should mention @AILocked PaymentProcessor");
        assertTrue(content.contains("Do not modify"), "Should have do-not-modify instruction");
    }

    @Test
    void testSweepYamlHasAuditRule() throws IOException {
        String content = harness.readFile("sweep.yaml");

        assertTrue(content.contains("Security audit required"), "Should have security audit rule");
        assertTrue(content.contains("DatabaseConnector"), "Should mention @AIAudit DatabaseConnector");
        assertTrue(content.contains("SQL Injection"), "Should include SQL Injection");
    }

    @Test
    void testSweepYamlHasPrivacyRule() throws IOException {
        String content = harness.readFile("sweep.yaml");

        assertTrue(content.contains("PII protection required"), "Should have PII protection rule");
    }

    // -----------------------------------------------------------------------
    // Plandex .plandex.yaml
    // -----------------------------------------------------------------------

    @Test
    void testPlandexYamlHasCorrectStructure() throws IOException {
        String content = harness.readFile(".plandex.yaml");

        assertTrue(content.contains("AUTO-GENERATED BY VIBETAGS"), "Should have auto-generated header");
        assertTrue(content.contains("guardrails:"), "Should have guardrails: key");
    }

    @Test
    void testPlandexYamlHasLockedSection() throws IOException {
        String content = harness.readFile(".plandex.yaml");

        assertTrue(content.contains("locked:"), "Should have locked: section");
        assertTrue(content.contains("PaymentProcessor"), "Should mention @AILocked PaymentProcessor");
    }

    @Test
    void testPlandexYamlHasAuditSection() throws IOException {
        String content = harness.readFile(".plandex.yaml");

        assertTrue(content.contains("audit:"), "Should have audit: section");
        assertTrue(content.contains("DatabaseConnector"), "Should mention @AIAudit DatabaseConnector");
    }

    // -----------------------------------------------------------------------
    // Double.bot .doubleignore
    // -----------------------------------------------------------------------

    @Test
    void testDoubleIgnoreHasIgnoredPattern() throws IOException {
        String content = harness.readFile(".doubleignore");

        assertTrue(content.contains("AUTO-GENERATED BY VIBETAGS"), "Should have auto-generated header");
        assertTrue(content.contains("GeneratedMetadata"), "Should have @AIIgnore GeneratedMetadata glob");
        assertTrue(content.contains(".java"), "Should use .java glob patterns");
    }

    // -----------------------------------------------------------------------
    // Codeium .codeiumignore
    // -----------------------------------------------------------------------

    @Test
    void testCodeiumIgnoreHasIgnoredPattern() throws IOException {
        String content = harness.readFile(".codeiumignore");

        assertTrue(content.contains("AUTO-GENERATED BY VIBETAGS"), "Should have auto-generated header");
        assertTrue(content.contains("GeneratedMetadata"), "Should have @AIIgnore GeneratedMetadata glob");
        assertTrue(content.contains(".java"), "Should use .java glob patterns");
    }

    // -----------------------------------------------------------------------
    // Open Interpreter .interpreter/profiles/vibetags.yaml
    // -----------------------------------------------------------------------

    @Test
    void testInterpreterProfileHasCorrectStructure() throws IOException {
        String content = harness.readFile(".interpreter/profiles/vibetags.yaml");

        assertTrue(content.contains("AUTO-GENERATED BY VIBETAGS"), "Should have auto-generated header");
        assertTrue(content.contains("instructions:"), "Should have instructions: key");
    }

    @Test
    void testInterpreterProfileHasLockedFiles() throws IOException {
        String content = harness.readFile(".interpreter/profiles/vibetags.yaml");

        assertTrue(content.contains("PaymentProcessor"), "Should mention @AILocked PaymentProcessor");
        assertTrue(content.contains("locked"), "Should indicate locked status");
    }

    @Test
    void testInterpreterProfileHasAuditRules() throws IOException {
        String content = harness.readFile(".interpreter/profiles/vibetags.yaml");

        assertTrue(content.contains("DatabaseConnector"), "Should mention @AIAudit DatabaseConnector");
        assertTrue(content.contains("SQL Injection"), "Should include SQL Injection");
    }

    @Test
    void testInterpreterProfileHasIgnoredElements() throws IOException {
        String content = harness.readFile(".interpreter/profiles/vibetags.yaml");

        assertTrue(content.contains("GeneratedMetadata"), "Should mention @AIIgnore GeneratedMetadata");
    }

    // -----------------------------------------------------------------------
    // Markers — ignore files use hash-comment markers; YAML/JSON get full overwrite
    // -----------------------------------------------------------------------

    @Test
    void testDoubleIgnoreHasVibeTagsMarkers() throws IOException {
        String content = harness.readFile(".doubleignore");

        assertTrue(content.contains("# VIBETAGS-START"), "Should have hash-comment start marker");
        assertTrue(content.contains("# VIBETAGS-END"), "Should have hash-comment end marker");
    }

    @Test
    void testCodeiumIgnoreHasVibeTagsMarkers() throws IOException {
        String content = harness.readFile(".codeiumignore");

        assertTrue(content.contains("# VIBETAGS-START"), "Should have hash-comment start marker");
        assertTrue(content.contains("# VIBETAGS-END"), "Should have hash-comment end marker");
    }
}
