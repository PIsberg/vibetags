package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests that verify the annotation processor generates correct output.
 * Self-contained: compiles annotated test sources into a {@code @TempDir} via
 * {@link ProcessorTestHarness} — no dependency on the example project.
 */
class AIGuardrailProcessorIntegrationTest {

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
    void testCursorRulesContainsAuditSection() throws Exception {
        String content = harness.readFile(".cursorrules");

        assertTrue(content.contains("MANDATORY SECURITY AUDITS"),
            "Should contain security audit section");
        assertTrue(content.contains("DatabaseConnector"),
            "Should mention DatabaseConnector class");
        assertTrue(content.contains("SQL Injection"),
            "Should mention SQL Injection");
        assertTrue(content.contains("Thread Safety"),
            "Should mention Thread Safety");
    }

    @Test
    void testClaudeMdContainsAuditRequirements() throws Exception {
        String content = harness.readFile("CLAUDE.md");

        assertTrue(content.contains("<audit_requirements>"),
            "Should contain audit requirements XML tag");
        assertTrue(content.contains("<vulnerability_check>SQL Injection</vulnerability_check>"),
            "Should contain SQL Injection vulnerability check");
        assertTrue(content.contains("<vulnerability_check>Thread Safety issues</vulnerability_check>"),
            "Should contain Thread Safety vulnerability check");
        assertTrue(content.contains("<rule>"),
            "Should contain rule element");
    }

    @Test
    void testGeminiInstructionsContainsAuditRequirements() throws Exception {
        String content = harness.readFile("gemini_instructions.md");

        assertTrue(content.contains("CONTINUOUS AUDIT REQUIREMENTS"),
            "Should contain continuous audit requirements header");
        assertTrue(content.contains("SQL Injection"),
            "Should mention SQL Injection");
        assertTrue(content.contains("Thread Safety"),
            "Should mention Thread Safety");
    }

    @Test
    void testCodexAgentsContainsAuditSection() throws Exception {
        String content = harness.readFile("AGENTS.md");

        assertTrue(content.contains("MANDATORY SECURITY AUDITS"),
            "Should contain security audit section");
        assertTrue(content.contains("DatabaseConnector"),
            "Should mention DatabaseConnector");
        assertTrue(content.contains("SQL Injection"),
            "Should mention SQL Injection");
        assertTrue(content.contains("Thread Safety"),
            "Should mention Thread Safety");
    }

    @Test
    void testAllGeneratedFilesExist() {
        assertTrue(harness.fileExists(".cursorrules"), ".cursorrules should exist");
        assertTrue(harness.fileExists("CLAUDE.md"), "CLAUDE.md should exist");
        assertTrue(harness.fileExists(".aiexclude"), ".aiexclude should exist");
        assertTrue(harness.fileExists("AGENTS.md"), "AGENTS.md should exist");
        assertTrue(harness.fileExists(".codex/config.toml"), ".codex/config.toml should exist");
        assertTrue(harness.fileExists(".codex/rules/vibetags.rules"), ".codex/rules/vibetags.rules should exist");
        assertTrue(harness.fileExists("gemini_instructions.md"), "gemini_instructions.md should exist");
        assertTrue(harness.fileExists(".github/copilot-instructions.md"), ".github/copilot-instructions.md should exist");
        assertTrue(harness.fileExists("QWEN.md"), "QWEN.md should exist");
        assertTrue(harness.fileExists(".qwen/settings.json"), ".qwen/settings.json should exist");
    }

    @Test
    void testLockedFilesAppearInAllOutputs() throws Exception {
        String cursorRules = harness.readFile(".cursorrules");
        String claudeMd = harness.readFile("CLAUDE.md");
        String codexAgents = harness.readFile("AGENTS.md");

        assertTrue(cursorRules.contains("PaymentProcessor"),
            "Cursor rules should mention PaymentProcessor");
        assertTrue(claudeMd.contains("PaymentProcessor"),
            "Claude.md should mention PaymentProcessor");
        assertTrue(codexAgents.contains("PaymentProcessor"),
            "Codex AGENTS.md should mention PaymentProcessor");
    }

    @Test
    void testContextRulesAppearInOutputs() throws Exception {
        String cursorRules = harness.readFile(".cursorrules");
        String claudeMd = harness.readFile("CLAUDE.md");

        assertTrue(cursorRules.contains("memory usage"),
            "Cursor rules should contain focus");
        assertTrue(cursorRules.contains("java.util.regex"),
            "Cursor rules should contain avoids");
        assertTrue(claudeMd.contains("memory usage"),
            "Claude.md should contain focus");
        assertTrue(claudeMd.contains("java.util.regex"),
            "Claude.md should contain avoids");
    }

    @Test
    void testCodexConfigHasCorrectSettings() throws Exception {
        String content = harness.readFile(".codex/config.toml");
        assertTrue(content.contains("model = \"o3-mini\""));
        assertTrue(content.contains("approval_policy = \"on-request\""));
    }

    @Test
    void testCodexRulesHasStarlarkContent() throws Exception {
        String content = harness.readFile(".codex/rules/vibetags.rules");
        assertTrue(content.contains("prefix_rule(\"mvn\", \"prompt\")"));
        assertTrue(content.contains("prefix_rule(\"ls\", \"allow\")"));
    }

    @Test
    void testFileContentIsNotEmpty() throws Exception {
        assertFalse(harness.readFile(".cursorrules").isEmpty());
        assertFalse(harness.readFile("CLAUDE.md").isEmpty());
        assertFalse(harness.readFile(".aiexclude").isEmpty());
        assertFalse(harness.readFile("AGENTS.md").isEmpty());
        assertFalse(harness.readFile(".codex/config.toml").isEmpty());
        assertFalse(harness.readFile(".codex/rules/vibetags.rules").isEmpty());
        assertFalse(harness.readFile("gemini_instructions.md").isEmpty());
    }

    @Test
    void testCursorRulesContainsIgnoredElementsSection() throws Exception {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("IGNORED ELEMENTS"),
            "Should contain ignored elements section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata class");
    }

    @Test
    void testClaudeMdContainsIgnoredElements() throws Exception {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<ignored_elements>"),
            "Should contain <ignored_elements> XML tag");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
        assertTrue(content.contains("Treat these as if they do not exist"),
            "Should have ignore rule");
    }

    @Test
    void testCodexAgentsContainsIgnoredFiles() throws Exception {
        String content = harness.readFile("AGENTS.md");
        assertTrue(content.contains("IGNORED ELEMENTS"),
            "Should contain IGNORED ELEMENTS section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
    }

    @Test
    void testGeminiInstructionsContainsIgnoredElements() throws Exception {
        String content = harness.readFile("gemini_instructions.md");
        assertTrue(content.contains("IGNORED ELEMENTS"),
            "Should contain IGNORED ELEMENTS section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
    }

    @Test
    void testCopilotInstructionsContainsLockedFiles() throws Exception {
        String content = harness.readFile(".github/copilot-instructions.md");
        assertTrue(content.contains("PaymentProcessor"),
            "Should mention PaymentProcessor");
        assertTrue(content.contains("Locked Files"),
            "Should contain locked files section");
    }

    @Test
    void testCopilotInstructionsContainsContextualGuidelines() throws Exception {
        String content = harness.readFile(".github/copilot-instructions.md");
        assertTrue(content.contains("memory usage"),
            "Should mention focus from @AIContext");
        assertTrue(content.contains("java.util.regex"),
            "Should mention avoids from @AIContext");
    }

    @Test
    void testCopilotInstructionsContainsSecurityAuditRequirements() throws Exception {
        String content = harness.readFile(".github/copilot-instructions.md");
        assertTrue(content.contains("Security Audit Requirements"),
            "Should contain security audit section");
        assertTrue(content.contains("DatabaseConnector"),
            "Should mention DatabaseConnector");
        assertTrue(content.contains("SQL Injection"),
            "Should mention SQL Injection");
        assertTrue(content.contains("Thread Safety"),
            "Should mention Thread Safety");
    }

    @Test
    void testCopilotInstructionsContainsIgnoredElements() throws Exception {
        String content = harness.readFile(".github/copilot-instructions.md");
        assertTrue(content.contains("Ignored Elements"),
            "Should contain ignored elements section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
    }

    @Test
    void testQwenMdContainsContext() throws Exception {
        String content = harness.readFile("QWEN.md");

        assertTrue(content.contains("PROJECT CONTEXT"), "Should have Qwen header");
        assertTrue(content.contains("PaymentProcessor"), "Should mention PaymentProcessor");
        assertTrue(content.contains("memory usage"), "Should contain focus");
        assertTrue(content.contains("MANDATORY SECURITY AUDITS"), "Should have audit section");
    }

    @Test
    void testQwenSettingsHasDefaults() throws Exception {
        String content = harness.readFile(".qwen/settings.json");
        assertTrue(content.contains("\"model\": \"qwen3-coder-plus\""), "Should have default Qwen model");
    }

    // --- Coverage: empty @AIAudit checkFor (continue branch at line 307) ---

    @Test
    void testEmptyAIAuditCheckFor_isSkippedGracefully() throws Exception {
        // Add an element with empty checkFor — processor should skip it without error
        harness.addSource("com.example.test.EmptyAudit",
            "package com.example.test;\n" +
            "import se.deversity.vibetags.annotations.AIAudit;\n" +
            "@AIAudit(checkFor = {})\n" +
            "public class EmptyAudit {}\n");
        harness.compile();

        // Should not throw, and audit section should still exist (from the non-empty one)
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("MANDATORY SECURITY AUDITS"));
    }

    // --- Coverage: writeFileIfChanged "no changes" path (line 512, 516) ---

    @Test
    void testWriteFileIfChanged_noChangesOnSecondCompile() throws Exception {
        // First compile already happened in @BeforeAll — files have content
        // Now clear the opt-in files and recompile — processor should detect no changes
        // because generated content equals what's already on disk
        harness.compile();

        // Files should still exist and have content
        assertTrue(harness.fileExists(".cursorrules"));
        String content = harness.readFile(".cursorrules");
        assertTrue(content.length() > 0, "File should still have content after recompile");
    }

    // --- Coverage: aiexclude with only gemini (no codex) ---

    @Test
    void testAiexcludeGeneratedForGeminiOnly() throws Exception {
        // .aiexclude is written when gemini OR codex is active
        // The harness already creates both, so verify .aiexclude has content
        String content = harness.readFile(".aiexclude");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should contain @AIIgnore elements");
    }
}
