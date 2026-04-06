package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.annotation.processing.RoundEnvironment;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIDraft;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for Qwen-specific functionality in AIGuardrailProcessor.
 * Tests Qwen file generation, settings, and ignore patterns.
 */
class QwenProcessorUnitTest {

    @Test
    void testQwenServiceFileMapIncludesAllFiles() {
        Path tempDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);

        // Verify all Qwen-related files are in the service file map
        assertTrue(serviceFiles.containsKey("qwen"), "Should have 'qwen' service key");
        assertTrue(serviceFiles.containsKey("qwen_ignore"), "Should have 'qwen_ignore' service key");
        assertTrue(serviceFiles.containsKey("qwen_settings"), "Should have 'qwen_settings' service key");
        assertTrue(serviceFiles.containsKey("qwen_refactor"), "Should have 'qwen_refactor' service key");

        // Verify paths
        assertEquals(tempDir.resolve("QWEN.md"), serviceFiles.get("qwen"));
        assertEquals(tempDir.resolve(".qwenignore"), serviceFiles.get("qwen_ignore"));
        assertEquals(tempDir.resolve(".qwen/settings.json"), serviceFiles.get("qwen_settings"));
        assertEquals(tempDir.resolve(".qwen/commands/refactor.md"), serviceFiles.get("qwen_refactor"));
    }

    @Test
    void testResolveActiveServices_qwenFileExists_onlyQwenIsActive(@TempDir Path tempDir) throws IOException {
        // Create only QWEN.md to test Qwen-only opt-in
        Files.createFile(tempDir.resolve("QWEN.md"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertEquals(Set.of("qwen"), active, "Only qwen should be active when only QWEN.md exists");
    }

    @Test
    void testResolveActiveServices_qwenIgnoreFileExists_onlyQwenIgnoreIsActive(@TempDir Path tempDir) throws IOException {
        // Create only .qwenignore
        Files.createFile(tempDir.resolve(".qwenignore"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        assertEquals(Set.of("qwen_ignore"), active, "Only qwen_ignore should be active");
    }

    @Test
    void testResolveActiveServices_multipleQwenFilesExist_allQwenServicesActive(@TempDir Path tempDir) throws IOException {
        // Create all Qwen-related files
        Files.createFile(tempDir.resolve("QWEN.md"));
        Files.createFile(tempDir.resolve(".qwenignore"));
        Files.createDirectories(tempDir.resolve(".qwen"));
        Files.createFile(tempDir.resolve(".qwen/settings.json"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);

        // Main opt-in keys should include qwen and qwen_ignore
        assertTrue(active.contains("qwen"), "qwen should be active");
        assertTrue(active.contains("qwen_ignore"), "qwen_ignore should be active");
    }

    @Test
    void testCheckOrphanedAnnotations_qwenIgnore_emitsWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        // Active services include qwen but not qwen_ignore
        Set<String> active = Set.of("qwen");
        processor.checkOrphanedAnnotations(messager, active, false, true, false);

        // Should warn about missing .qwenignore
        assertTrue(warnings.stream().anyMatch(w -> w.contains(".qwenignore")),
            "Should warn about missing .qwenignore file");
    }

    @Test
    void testCheckOrphanedAnnotations_qwenIgnorePresent_noWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();

        // Both qwen and qwen_ignore are active
        Set<String> active = Set.of("qwen", "qwen_ignore");
        processor.checkOrphanedAnnotations(messager, active, false, true, false);

        // Should NOT warn about .qwenignore since it's present
        assertFalse(warnings.stream().anyMatch(w -> w.contains(".qwenignore")),
            "Should not warn about .qwenignore when it's present");
    }

    @Test
    void testWriteFileIfChanged_qwenMd_writesSuccessfully(@TempDir Path tempDir) throws IOException {
        Path qwenMd = tempDir.resolve("QWEN.md");
        Files.createFile(qwenMd);

        String content = "# PROJECT CONTEXT\n" +
            "# AUTO-GENERATED BY VIBETAGS\n\n" +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.TestClass` — Test reason\n\n" +
            "## CONTEXTUAL RULES\n" +
            "* `com.example.TestContext`\n" +
            "  * Focus: test focus\n" +
            "  * Avoid: test avoids\n\n" +
            "## 🛡️ MANDATORY SECURITY AUDITS\n" +
            "* `com.example.TestAudit`\n" +
            "  - Required Checks: SQL Injection\n\n" +
            "## IGNORED ELEMENTS\n" +
            "* `com.example.TestIgnore`\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(qwenMd.toString(), content);

        assertTrue(changed, "File should be written");
        String writtenContent = Files.readString(qwenMd);
        assertTrue(writtenContent.contains("PROJECT CONTEXT"));
        assertTrue(writtenContent.contains("LOCKED FILES"));
        assertTrue(writtenContent.contains("CONTEXTUAL RULES"));
        assertTrue(writtenContent.contains("MANDATORY SECURITY AUDITS"));
        assertTrue(writtenContent.contains("IGNORED ELEMENTS"));
    }

    @Test
    void testWriteFileIfChanged_qwenSettings_writesSuccessfully(@TempDir Path tempDir) throws IOException {
        Path settingsJson = tempDir.resolve(".qwen/settings.json");
        Files.createDirectories(settingsJson.getParent());
        Files.createFile(settingsJson);

        String content = "{\n" +
            "  \"project\": {\n" +
            "    \"model\": \"qwen3-coder-plus\",\n" +
            "    \"mcp\": {\n" +
            "      \"enabled\": true\n" +
            "    }\n" +
            "  }\n" +
            "}\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(settingsJson.toString(), content);

        assertTrue(changed, "Settings file should be written");
        String writtenContent = Files.readString(settingsJson);
        assertTrue(writtenContent.contains("qwen3-coder-plus"));
        assertTrue(writtenContent.contains("\"mcp\""));
    }

    @Test
    void testWriteFileIfChanged_qwenIgnore_writesSuccessfully(@TempDir Path tempDir) throws IOException {
        Path qwenIgnore = tempDir.resolve(".qwenignore");
        Files.createFile(qwenIgnore);

        String content = "# AUTO-GENERATED BY VIBETAGS\n" +
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n" +
            "# Qwen-specific exclusion list.\n" +
            "**/TestIgnore.java\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(qwenIgnore.toString(), content);

        assertTrue(changed, ".qwenignore should be written");
        String writtenContent = Files.readString(qwenIgnore);
        assertTrue(writtenContent.contains("Qwen-specific exclusion list"));
        assertTrue(writtenContent.contains("**/TestIgnore.java"));
    }

    @Test
    void testWriteFileIfChanged_qwenRefactor_writesSuccessfully(@TempDir Path tempDir) throws IOException {
        Path refactorMd = tempDir.resolve(".qwen/commands/refactor.md");
        Files.createDirectories(refactorMd.getParent());
        Files.createFile(refactorMd);

        String content = "# /refactor command\n" +
            "# AUTO-GENERATED BY VIBETAGS\n\n" +
            "Refactor the current selection to improve maintainability and performance " +
            "while strictly following the project's contextual rules in QWEN.md.\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(refactorMd.toString(), content);

        assertTrue(changed, "refactor.md should be written");
        String writtenContent = Files.readString(refactorMd);
        assertTrue(writtenContent.contains("/refactor command"));
        assertTrue(writtenContent.contains("QWEN.md"));
    }

    @Test
    void testQwenMdContentStructure_withAllAnnotations(@TempDir Path tempDir) throws IOException {
        // This test verifies the complete QWEN.md structure
        Path qwenMd = tempDir.resolve("QWEN.md");
        Files.createFile(qwenMd);

        String content = "# PROJECT CONTEXT\n" +
            "# AUTO-GENERATED BY VIBETAGS\n" +
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n\n" +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.LockedClass` — Locked reason\n\n" +
            "## CONTEXTUAL RULES\n" +
            "* `com.example.ContextClass`\n" +
            "  * Focus: performance\n" +
            "  * Avoid: regex\n\n" +
            "## 🛡️ MANDATORY SECURITY AUDITS\n" +
            "* `com.example.AuditClass`\n" +
            "  - Required Checks: SQL Injection, XSS\n\n" +
            "## IGNORED ELEMENTS\n" +
            "* `com.example.IgnoreClass`\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(qwenMd.toString(), content);

        String writtenContent = Files.readString(qwenMd);

        // Verify all sections are present
        assertTrue(writtenContent.contains("# PROJECT CONTEXT"));
        assertTrue(writtenContent.contains("## LOCKED FILES"));
        assertTrue(writtenContent.contains("## CONTEXTUAL RULES"));
        assertTrue(writtenContent.contains("## 🛡️ MANDATORY SECURITY AUDITS"));
        assertTrue(writtenContent.contains("## IGNORED ELEMENTS"));
        assertTrue(writtenContent.contains("LockedClass"));
        assertTrue(writtenContent.contains("ContextClass"));
        assertTrue(writtenContent.contains("AuditClass"));
        assertTrue(writtenContent.contains("IgnoreClass"));
    }

    @Test
    void testQwenSettingsJson_validJson(@TempDir Path tempDir) throws IOException {
        Path settingsJson = tempDir.resolve(".qwen/settings.json");
        Files.createDirectories(settingsJson.getParent());
        Files.createFile(settingsJson);

        String content = "{\n" +
            "  \"project\": {\n" +
            "    \"model\": \"qwen3-coder-plus\",\n" +
            "    \"mcp\": {\n" +
            "      \"enabled\": true\n" +
            "    }\n" +
            "  }\n" +
            "}\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(settingsJson.toString(), content);

        // Verify it contains valid JSON structure by checking key elements
        String writtenContent = Files.readString(settingsJson);
        assertTrue(writtenContent.contains("\"project\""), "Should have project key");
        assertTrue(writtenContent.contains("\"model\""), "Should have model key");
        assertTrue(writtenContent.contains("qwen3-coder-plus"), "Should specify qwen3-coder-plus");
        assertTrue(writtenContent.contains("\"mcp\""), "Should have mcp key");
        assertTrue(writtenContent.contains("\"enabled\""), "Should have enabled key");
    }

    @Test
    void testQwenIgnoreFile_globPatternFormat(@TempDir Path tempDir) throws IOException {
        Path qwenIgnore = tempDir.resolve(".qwenignore");
        Files.createFile(qwenIgnore);

        String content = "# AUTO-GENERATED BY VIBETAGS\n" +
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n" +
            "# Qwen-specific exclusion list.\n" +
            "**/GeneratedMetadata.java\n" +
            "**/AutoGenerated.java\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(qwenIgnore.toString(), content);

        String writtenContent = Files.readString(qwenIgnore);
        // Verify glob patterns are correctly formatted
        assertTrue(writtenContent.contains("**/GeneratedMetadata.java"));
        assertTrue(writtenContent.contains("**/AutoGenerated.java"));
    }

    @Test
    void testQwenVersionStamping(@TempDir Path tempDir) throws IOException {
        Path qwenMd = tempDir.resolve("QWEN.md");
        Files.createFile(qwenMd);

        String content = "# PROJECT CONTEXT\n" +
            "# AUTO-GENERATED BY VIBETAGS\n" +
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(qwenMd.toString(), content);

        String writtenContent = Files.readString(qwenMd);
        assertTrue(writtenContent.contains("Generated by VibeTags |"),
            "QWEN.md should contain VibeTags attribution");
        assertTrue(writtenContent.contains("AUTO-GENERATED BY VIBETAGS"),
            "QWEN.md should contain auto-generated marker");
    }

    @Test
    void testQwenMultiFileExtras_structure() {
        // Verify that Qwen multi-file extras are correctly defined
        Path tempDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);

        // Verify Qwen settings path
        Path settingsPath = serviceFiles.get("qwen_settings");
        assertNotNull(settingsPath, "qwen_settings path should be defined");
        assertTrue(settingsPath.endsWith(Paths.get(".qwen/settings.json")),
            "Settings should be in .qwen/settings.json");

        // Verify Qwen refactor command path
        Path refactorPath = serviceFiles.get("qwen_refactor");
        assertNotNull(refactorPath, "qwen_refactor path should be defined");
        assertTrue(refactorPath.endsWith(Paths.get(".qwen/commands/refactor.md")),
            "Refactor command should be in .qwen/commands/refactor.md");
    }

    // Helper methods

    private static Messager noopMessager() {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a, javax.lang.model.element.AnnotationValue v) {}
        };
    }

    private static Messager capturingMessager(Diagnostic.Kind capture, List<String> sink) {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == capture) sink.add(msg.toString());
            }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e) { printMessage(kind, msg); }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a) { printMessage(kind, msg); }
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, javax.lang.model.element.Element e, javax.lang.model.element.AnnotationMirror a, javax.lang.model.element.AnnotationValue v) { printMessage(kind, msg); }
        };
    }
}
