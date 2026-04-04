package se.deversity.vibetags.processor;

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
    private File originalCodexAgents;
    private File originalCodexConfig;
    private File originalCodexRules;
    private File originalGeminiMd;
    private File originalCopilotMd;
    private File originalCursorIgnore;
    private File originalCopilotIgnore;

    private static final String EXAMPLE_DIR = "../example";

    @BeforeEach
    void setUp() throws IOException {
        // Backup existing files if they exist
        originalCursorRules = backupFile(new File(EXAMPLE_DIR, ".cursorrules"));
        originalClaudeMd = backupFile(new File(EXAMPLE_DIR, "CLAUDE.md"));
        originalAiExclude = backupFile(new File(EXAMPLE_DIR, ".aiexclude"));
        originalCodexAgents = backupFile(new File(EXAMPLE_DIR, "AGENTS.md"));
        originalCodexConfig = backupFile(new File(EXAMPLE_DIR, ".codex/config.toml"));
        originalCodexRules = backupFile(new File(EXAMPLE_DIR, ".codex/rules/vibetags.rules"));
        originalGeminiMd = backupFile(new File(EXAMPLE_DIR, "gemini_instructions.md"));
        originalCopilotMd = backupFile(new File(EXAMPLE_DIR, ".github/copilot-instructions.md"));
        originalCursorIgnore = backupFile(new File(EXAMPLE_DIR, ".cursorignore"));
        originalCopilotIgnore = backupFile(new File(EXAMPLE_DIR, ".copilotignore"));
    }

    @AfterEach
    void tearDown() throws IOException {
        // Restore original files
        restoreFile(originalCursorRules, new File(EXAMPLE_DIR, ".cursorrules"));
        restoreFile(originalClaudeMd, new File(EXAMPLE_DIR, "CLAUDE.md"));
        restoreFile(originalAiExclude, new File(EXAMPLE_DIR, ".aiexclude"));
        restoreFile(originalCodexAgents, new File(EXAMPLE_DIR, "AGENTS.md"));
        restoreFile(originalCodexConfig, new File(EXAMPLE_DIR, ".codex/config.toml"));
        restoreFile(originalCodexRules, new File(EXAMPLE_DIR, ".codex/rules/vibetags.rules"));
        restoreFile(originalGeminiMd, new File(EXAMPLE_DIR, "gemini_instructions.md"));
        restoreFile(originalCopilotMd, new File(EXAMPLE_DIR, ".github/copilot-instructions.md"));
        restoreFile(originalCursorIgnore, new File(EXAMPLE_DIR, ".cursorignore"));
        restoreFile(originalCopilotIgnore, new File(EXAMPLE_DIR, ".copilotignore"));
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
    void testCodexAgentsContainsAuditSection() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/AGENTS.md");
        
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
    void testAllGeneratedFilesExist() throws Exception {
        // Verify all files were created
        assertTrue(new File(EXAMPLE_DIR, ".cursorrules").exists(), ".cursorrules should exist");
        assertTrue(new File(EXAMPLE_DIR, "CLAUDE.md").exists(), "CLAUDE.md should exist");
        assertTrue(new File(EXAMPLE_DIR, ".aiexclude").exists(), ".aiexclude should exist");
        assertTrue(new File(EXAMPLE_DIR, "AGENTS.md").exists(), "AGENTS.md should exist");
        assertTrue(new File(EXAMPLE_DIR, ".codex/config.toml").exists(), ".codex/config.toml should exist");
        assertTrue(new File(EXAMPLE_DIR, ".codex/rules/vibetags.rules").exists(), ".codex/rules/vibetags.rules should exist");
        assertTrue(new File(EXAMPLE_DIR, "gemini_instructions.md").exists(), "gemini_instructions.md should exist");
        assertTrue(new File(EXAMPLE_DIR, ".github/copilot-instructions.md").exists(), ".github/copilot-instructions.md should exist");
    }

    @Test
    void testLockedFilesAppearInAllOutputs() throws Exception {
        String cursorRules = readFile(EXAMPLE_DIR + "/.cursorrules");
        String claudeMd = readFile(EXAMPLE_DIR + "/CLAUDE.md");
        String codexAgents = readFile(EXAMPLE_DIR + "/AGENTS.md");

        // All should mention the locked class
        assertTrue(cursorRules.contains("PaymentProcessor"), 
            "Cursor rules should mention PaymentProcessor");
        assertTrue(claudeMd.contains("PaymentProcessor"), 
            "Claude.md should mention PaymentProcessor");
        assertTrue(codexAgents.contains("PaymentProcessor"), 
            "Codex AGENTS.md should mention PaymentProcessor");
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
        assertTrue(claudeMd.contains("memory usage"),
            "Claude.md should contain focus");
        assertTrue(claudeMd.contains("java.util.regex"),
            "Claude.md should contain avoids");
    }

    @Test
    void testCodexConfigHasCorrectSettings() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.codex/config.toml");
        assertTrue(content.contains("model = \"o3-mini\""));
        assertTrue(content.contains("approval_policy = \"on-request\""));
    }

    @Test
    void testCodexRulesHasStarlarkContent() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.codex/rules/vibetags.rules");
        assertTrue(content.contains("prefix_rule(\"mvn\", \"prompt\")"));
        assertTrue(content.contains("prefix_rule(\"ls\", \"allow\")"));
    }

    @Test
    void testFileContentIsNotEmpty() throws Exception {
        // All generated files should have content
        assertFalse(readFile(EXAMPLE_DIR + "/.cursorrules").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/CLAUDE.md").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/.aiexclude").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/AGENTS.md").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/.codex/config.toml").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/.codex/rules/vibetags.rules").isEmpty());
        assertFalse(readFile(EXAMPLE_DIR + "/gemini_instructions.md").isEmpty());
    }

    @Test
    void testCursorRulesContainsIgnoredElementsSection() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.cursorrules");
        assertTrue(content.contains("IGNORED ELEMENTS"),
            "Should contain ignored elements section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata class");
    }

    @Test
    void testClaudeMdContainsIgnoredElements() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/CLAUDE.md");
        assertTrue(content.contains("<ignored_elements>"),
            "Should contain <ignored_elements> XML tag");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
        assertTrue(content.contains("Treat these as if they do not exist"),
            "Should have ignore rule");
    }

    @Test
    void testCodexAgentsContainsIgnoredFiles() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/AGENTS.md");
        assertTrue(content.contains("IGNORED ELEMENTS"),
            "Should contain IGNORED ELEMENTS section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
    }

    @Test
    void testGeminiInstructionsContainsIgnoredElements() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/gemini_instructions.md");
        assertTrue(content.contains("IGNORED ELEMENTS"),
            "Should contain IGNORED ELEMENTS section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
    }

    @Test
    void testCopilotInstructionsContainsLockedFiles() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.github/copilot-instructions.md");
        assertTrue(content.contains("PaymentProcessor"),
            "Should mention PaymentProcessor");
        assertTrue(content.contains("Locked Files"),
            "Should contain locked files section");
    }

    @Test
    void testCopilotInstructionsContainsContextualGuidelines() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.github/copilot-instructions.md");
        assertTrue(content.contains("memory usage"),
            "Should mention focus from @AIContext");
        assertTrue(content.contains("java.util.regex"),
            "Should mention avoids from @AIContext");
    }

    @Test
    void testCopilotInstructionsContainsSecurityAuditRequirements() throws Exception {
        String content = readFile(EXAMPLE_DIR + "/.github/copilot-instructions.md");
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
        String content = readFile(EXAMPLE_DIR + "/.github/copilot-instructions.md");
        assertTrue(content.contains("Ignored Elements"),
            "Should contain ignored elements section");
        assertTrue(content.contains("GeneratedMetadata"),
            "Should mention GeneratedMetadata");
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
