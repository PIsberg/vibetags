package com.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify the annotation processor generates correct output files.
 * These tests check the files generated when the example project is compiled.
 * 
 * Run with: mvn test -Drun.integration.tests=true
 */
@EnabledIfSystemProperty(named = "run.integration.tests", matches = "true")
class AIGuardrailProcessorIntegrationTest {

    private File originalCursorRules;
    private File originalClaudeMd;
    private File originalAiExclude;
    private File originalChatGptMd;
    private File originalGeminiMd;

    private static final String EXAMPLE_DIR = "../example";

    @BeforeEach
    void setUp() throws IOException {
        // Backup existing files if they exist
        originalCursorRules = backupFile(new File(EXAMPLE_DIR, ".cursorrules"));
        originalClaudeMd = backupFile(new File(EXAMPLE_DIR, "CLAUDE.md"));
        originalAiExclude = backupFile(new File(EXAMPLE_DIR, ".aiexclude"));
        originalChatGptMd = backupFile(new File(EXAMPLE_DIR, "chatgpt_instructions.md"));
        originalGeminiMd = backupFile(new File(EXAMPLE_DIR, "gemini_instructions.md"));

        // Clean up any existing generated files
        deleteIfExists(EXAMPLE_DIR + "/.cursorrules");
        deleteIfExists(EXAMPLE_DIR + "/CLAUDE.md");
        deleteIfExists(EXAMPLE_DIR + "/.aiexclude");
        deleteIfExists(EXAMPLE_DIR + "/chatgpt_instructions.md");
        deleteIfExists(EXAMPLE_DIR + "/gemini_instructions.md");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Restore original files
        restoreFile(originalCursorRules, new File(EXAMPLE_DIR, ".cursorrules"));
        restoreFile(originalClaudeMd, new File(EXAMPLE_DIR, "CLAUDE.md"));
        restoreFile(originalAiExclude, new File(EXAMPLE_DIR, ".aiexclude"));
        restoreFile(originalChatGptMd, new File(EXAMPLE_DIR, "chatgpt_instructions.md"));
        restoreFile(originalGeminiMd, new File(EXAMPLE_DIR, "gemini_instructions.md"));
    }

    @Test
    void testCursorRulesContainsAuditSection() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.cursorrules");
        
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
        String content = readFile(EXAMPLE_DIR + "/CLAUDE.md");
        
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
        String content = readFile(EXAMPLE_DIR + "/gemini_instructions.md");
        
        assertTrue(content.contains("CONTINUOUS AUDIT REQUIREMENTS"), 
            "Should contain continuous audit requirements header");
        assertTrue(content.contains("SQL Injection"), 
            "Should mention SQL Injection");
        assertTrue(content.contains("Thread Safety"), 
            "Should mention Thread Safety");
    }

    @Test
    void testChatGptInstructionsContainsGuardrails() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/chatgpt_instructions.md");
        
        assertTrue(content.contains("SECURITY GUARDRAILS"), 
            "Should contain security guardrails section");
        assertTrue(content.contains("DatabaseConnector"), 
            "Should mention DatabaseConnector");
        assertTrue(content.contains("SQL Injection"), 
            "Should mention SQL Injection");
        assertTrue(content.contains("Thread Safety"), 
            "Should mention Thread Safety");
    }

    @Test
    void testAllGeneratedFilesExist() throws Exception {
        // Verify all files were created
        assertTrue(new File(EXAMPLE_DIR, ".cursorrules").exists(), ".cursorrules should exist");
        assertTrue(new File(EXAMPLE_DIR, "CLAUDE.md").exists(), "CLAUDE.md should exist");
        assertTrue(new File(EXAMPLE_DIR, ".aiexclude").exists(), ".aiexclude should exist");
        assertTrue(new File(EXAMPLE_DIR, "chatgpt_instructions.md").exists(), "chatgpt_instructions.md should exist");
        assertTrue(new File(EXAMPLE_DIR, "gemini_instructions.md").exists(), "gemini_instructions.md should exist");
    }

    @Test
    void testLockedFilesAppearInAllOutputs() throws Exception {
        String cursorRules = readFile(EXAMPLE_DIR + "/.cursorrules");
        String claudeMd = readFile(EXAMPLE_DIR + "/CLAUDE.md");
        String chatGpt = readFile(EXAMPLE_DIR + "/chatgpt_instructions.md");

        // All should mention the locked class
        assertTrue(cursorRules.contains("PaymentProcessor"), 
            "Cursor rules should mention PaymentProcessor");
        assertTrue(claudeMd.contains("PaymentProcessor"), 
            "Claude.md should mention PaymentProcessor");
        assertTrue(chatGpt.contains("PaymentProcessor"), 
            "ChatGPT instructions should mention PaymentProcessor");
    }

    @Test
    void testContextRulesAppearInOutputs() throws Exception {
        String cursorRules = readFile(EXAMPLE_DIR + "/.cursorrules");
        String claudeMd = readFile(EXAMPLE_DIR + "/CLAUDE.md");

        // Should contain context information
        assertTrue(cursorRules.contains("memory usage"), 
            "Cursor rules should contain focus");
        assertTrue(cursorRules.contains("java.util.regex"), 
            "Cursor rules should contain avoids");
        assertTrue(claudeMd.contains("Memory optimization"), 
            "Claude.md should contain focus");
        assertTrue(claudeMd.contains("java.util.regex"), 
            "Claude.md should contain avoids");
    }

    @Test
    void testAuditChecklistFormatInChatGpt() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/chatgpt_instructions.md");
        
        // Should have numbered checklist
        assertTrue(content.contains("1."));
        assertTrue(content.contains("Is this code vulnerable to"));
        assertTrue(content.contains("discard your draft and rewrite"));
    }

    @Test
    void testFileContentIsNotEmpty() throws Exception {
        // All generated files should have content
        assertFalse(readFile(EXAMPLE_DIR + "/.cursorrules").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/CLAUDE.md").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/.aiexclude").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/chatgpt_instructions.md").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/gemini_instructions.md").isEmpty());
    }

    private File backupFile(File file) throws IOException {
        if (file.exists()) {
            File backup = File.createTempFile(file.getName() + ".backup", ".tmp");
            Files.copy(file.toPath(), backup.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return backup;
        }
        return null;
    }

    private void restoreFile(File backup, File target) throws IOException {
        if (backup != null && backup.exists()) {
            Files.copy(backup.toPath(), target.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            backup.delete();
        }
    }

    private void deleteIfExists(String filename) {
        File file = new File(filename);
        if (file.exists()) {
            file.delete();
        }
    }

    private String readFile(String filename) throws IOException {
        File file = new File(filename);
        if (!file.exists()) {
            return "";
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }
}
