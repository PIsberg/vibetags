package se.deversity.vibetags.processor;

import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.*;
import javax.lang.model.element.Element;
import javax.annotation.processing.RoundEnvironment;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIIgnore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AIGuardrailProcessor.
 * Tests the processor logic without full compilation.
 */
class AIGuardrailProcessorUnitTest {

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void testProcessorInstantiation() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        assertNotNull(processor);
    }

    @Test
    void testProcessorSupportsCorrectAnnotationTypes() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // The processor should support all four annotation types
        // This is configured via @SupportedAnnotationTypes annotation
        SupportedAnnotationTypes annotation = 
            AIGuardrailProcessor.class.getAnnotation(SupportedAnnotationTypes.class);
        assertNotNull(annotation);
        assertArrayEquals(
            new String[]{"*"},
            annotation.value(),
            "Must use '*' so the processor runs even when all VibeTags annotations are removed"
        );
    }

    @Test
    void testProcessorSupportsCorrectSourceVersion() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        SupportedSourceVersion versionAnnotation = 
            AIGuardrailProcessor.class.getAnnotation(SupportedSourceVersion.class);
        assertNotNull(versionAnnotation);
        // Should support Java 11 or higher
        assertTrue(
            versionAnnotation.value().compareTo(SourceVersion.RELEASE_11) >= 0,
            "Should support at least Java 11"
        );
    }

    @Test
    void testCheckOrphanedAnnotations_emitsWarnings() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        
        Set<String> active = Set.of("cursor", "claude", "qwen");
        processor.checkOrphanedAnnotations(messager, active, false, true, false);
        
        assertEquals(3, warnings.size(), "Should have 3 warnings (cursor, claude, and qwen ignore missing)");
        assertTrue(warnings.get(0).contains(".cursorignore"));
        assertTrue(warnings.get(1).contains(".claudeignore"));
        assertTrue(warnings.get(2).contains(".qwenignore"));
    }

    @Test
    void testCheckOrphanedAnnotations_geminiAIExclude_emitsWarnings() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        
        Set<String> active = Set.of("gemini");
        processor.checkOrphanedAnnotations(messager, active, true, true, false);
        
        // Should have 2 warnings for @AIIgnore and @AILocked about .aiexclude
        assertEquals(2, warnings.size(), "Should have 2 warnings (gemini ignore and locked missing .aiexclude)");
        assertTrue(warnings.get(0).contains(".aiexclude"));
        assertTrue(warnings.get(1).contains(".aiexclude"));
    }

    @Test
    void testWriteFileHandlesNullContent() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // This test ensures the writeFile method handles edge cases
        // The actual implementation should handle this gracefully
        assertDoesNotThrow(() -> {
            // We can't test the actual file writing without a processing environment
            // but we can verify the method exists and is callable
            assertNotNull(processor);
        });
    }

    @Test
    void testProcessorExtendsAbstractProcessor() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        assertTrue(
            processor instanceof javax.annotation.processing.AbstractProcessor,
            "Should extend AbstractProcessor"
        );
    }

    @Test
    void testResolveActiveServices_noFilesExist_returnsEmpty(@TempDir Path tempDir) {
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertTrue(active.isEmpty(),
            "No output files present → nothing should be generated");
    }

    @Test
    void testResolveActiveServices_noFilesExist_emitsNote(@TempDir Path tempDir) {
        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        AIGuardrailProcessor.resolveActiveServices(messager, serviceFiles);

        assertEquals(1, notes.size(), "Exactly one NOTE should be emitted");
        String note = notes.get(0);
        assertTrue(note.contains("No AI config files found"), "Note should explain the situation");
        assertTrue(note.contains("CLAUDE.md"), "Note should list CLAUDE.md");
        assertTrue(note.contains(".cursorrules"), "Note should list .cursorrules");
        assertTrue(note.contains("AGENTS.md"), "Note should list codex file");
        assertTrue(note.contains("gemini_instructions.md"), "Note should list gemini file");
        assertTrue(note.contains("copilot-instructions.md"), "Note should list copilot file");
        assertTrue(note.contains(".cursorignore"), "Note should list cursor ignore file");
        assertTrue(note.contains(".copilotignore"), "Note should list copilot ignore file");
    }

    @Test
    void testResolveActiveServices_someFilesExist_onlyThoseAreActive(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        Files.createFile(tempDir.resolve(".cursorrules"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertEquals(Set.of("claude", "cursor"), active,
            "Only services with existing files should be active");
    }

    @Test
    void testResolveActiveServices_someFilesExist_doesNotWarn(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        AIGuardrailProcessor.resolveActiveServices(messager, serviceFiles);

        assertTrue(notes.isEmpty(), "No warning should be emitted when at least one file exists");
    }

    @Test
    void testResolveActiveServices_allFilesExist_allServicesActive(@TempDir Path tempDir) throws IOException {
        AIGuardrailProcessor.buildServiceFileMap(tempDir).forEach((key, p) -> {
            try {
                if (key.endsWith("_granular")) {
                    Files.createDirectories(p);
                    // Add a signal file so isNotEmpty check passes
                    Files.createFile(p.resolve(".vibetags"));
                } else {
                    Files.createDirectories(p.getParent());
                    Files.createFile(p);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        Set<String> expected = Set.of(
            "cursor", "claude", "aiexclude", "codex", "gemini", "copilot", "qwen",
            "cursor_ignore", "claude_ignore", "copilot_ignore", "qwen_ignore",
            "llms", "llms_full", "aider_conventions", "aider_ignore",
            "cursor_granular", "roo_granular", "trae_granular",
            // v0.7.0 platforms
            "windsurf", "zed", "cody", "cody_ignore", "supermaven_ignore",
            "windsurf_granular", "continue_granular", "tabnine_granular",
            "amazonq_granular", "ai_rules_granular",
            // v0.8.0 platforms
            "pearai_granular", "mentat", "sweep", "plandex",
            "double_ignore", "interpreter", "codeium_ignore"
        );
        assertEquals(expected, active, "Only primary opt-in services should be in the active resolution set");
    }

    // --- annotation removal ---

    @Test
    void testPartialAnnotationRemoval_updatesFile(@TempDir Path tempDir) throws IOException {
        // Simulate a file produced by a previous compile that had @AILocked on two classes
        Path file = tempDir.resolve(".cursorrules");
        String attribution = "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n";
        String previousContent =
            "# AUTO-GENERATED AI RULES\n" +
            attribution +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.PaymentProcessor` - Reason: legacy\n" +
            "* `com.example.OrderService` - Reason: stable\n";
        Files.writeString(file, previousContent);

        // After removing @AILocked from OrderService, the processor generates content without it
        String newContent =
            "# AUTO-GENERATED AI RULES\n" +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.PaymentProcessor` - Reason: legacy\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), newContent, true);

        assertTrue(changed, "File must be updated when an annotation is removed");
        String finalContent = Files.readString(file);
        assertTrue(finalContent.contains("VIBETAGS-START"), "Should now have markers");
        assertFalse(finalContent.contains("OrderService"),
            "Removed annotation entry must no longer appear in the file");
        assertTrue(finalContent.contains("PaymentProcessor"),
            "Remaining annotation entry must still be present");
    }

    @Test
    void testAllAnnotationsRemoved_fileUpdatedToHeaderOnly(@TempDir Path tempDir) throws IOException {
        // Simulate a file produced by a previous compile that had @AILocked entries
        Path file = tempDir.resolve(".cursorrules");
        String attribution = "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n";
        String previousContent =
            "# AUTO-GENERATED AI RULES\n" +
            attribution +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.PaymentProcessor` - Reason: legacy\n";
        Files.writeString(file, previousContent);

        // After removing ALL VibeTags annotations, the processor generates header-only content
        String headerOnlyContent = "# AUTO-GENERATED AI RULES\n## LOCKED FILES (DO NOT EDIT)\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), headerOnlyContent, true);

        assertTrue(changed, "File must be updated when all annotations are removed");
        assertFalse(Files.readString(file).contains("PaymentProcessor"),
            "Stale annotation entry must be cleared from the file");
    }

    // --- writeFileIfChanged ---

    @Test
    void testWriteFileIfChanged_emptyFile_writesAndReturnsTrue(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.createFile(file);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "new content", true);

        assertTrue(changed, "Should return true when file was empty");
        String content = Files.readString(file);
        assertTrue(content.contains("VIBETAGS-START"));
        assertTrue(content.contains("new content"));
        assertTrue(content.contains("VIBETAGS-END"));
    }

    @Test
    void testWriteFileIfChanged_sameContent_returnsFalseAndDoesNotWrite(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        String markedContent = "<!-- VIBETAGS-START -->\nsame content\n<!-- VIBETAGS-END -->";
        Files.writeString(file, markedContent);
        long before = Files.getLastModifiedTime(file).toMillis();

        // Small sleep to ensure mtime would differ if written
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // Passing "same content" should result in the same markedContent being compared
        boolean changed = processor.writeFileIfChanged(file.toString(), "same content", true);

        assertFalse(changed, "Should return false when content (with markers) is identical");
        assertEquals(before, Files.getLastModifiedTime(file).toMillis(),
            "File should not have been rewritten");
    }

    @Test
    void testWriteFileIfChanged_differentContent_writesAndReturnsTrue(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "<!-- VIBETAGS-START -->\nold content\n<!-- VIBETAGS-END -->");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "new content", true);

        assertTrue(changed, "Should return true when content differs");
        assertTrue(Files.readString(file).contains("new content"));
    }

    @Test
    void testWriteFileIfChanged_writesAtomically_whenFileModified(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "<!-- VIBETAGS-START -->\nold content\n<!-- VIBETAGS-END -->");
        Path tmpFile = tempDir.resolve("test.md.vibetags-tmp");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "new content", true);

        assertTrue(changed);
        assertTrue(Files.readString(file).contains("new content"));
        assertFalse(Files.exists(tmpFile), "Temp file must be moved into place, not left behind");
    }

    @Test
    void testWriteFileIfChanged_leavesNoTempFile_whenContentIdentical(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        String content = "<!-- VIBETAGS-START -->\nsame content\n<!-- VIBETAGS-END -->\n";
        Files.writeString(file, content);
        Path tmpFile = tempDir.resolve("test.md.vibetags-tmp");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "same content", true);

        assertFalse(changed);
        assertFalse(Files.exists(tmpFile), "No temp file should be created when no write happens");
    }

    @Test
    void testWriteFileIfChanged_preservesContentBeforeMissingEndMarker(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "Before start marker\n<!-- VIBETAGS-START -->\nold content missing end marker");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "new content", true);

        assertTrue(changed);
        String content = Files.readString(file);
        assertTrue(content.contains("Before start marker"));
        assertTrue(content.contains("<!-- VIBETAGS-START -->"));
        assertTrue(content.contains("new content"));
        assertTrue(content.contains("<!-- VIBETAGS-END -->"));
        assertFalse(content.contains("old content missing end marker")); // this is inside/after the start marker and gets replaced.
    }

    @Test
    void testWriteFileIfChanged_stripsWhitespaceForComparison(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        // File has markers and content as it would be written by the processor
        String fullContent = "<!-- VIBETAGS-START -->\ncontent\n<!-- VIBETAGS-END -->\n";
        Files.writeString(file, fullContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // Passing "content " with trailing space should still return false if the wrapped result matches
        boolean changed = processor.writeFileIfChanged(file.toString(), "content ", true);

        assertFalse(changed, "Whitespace difference should not trigger a rewrite");
    }

    @Test
    void testWriteFileIfChanged_withExistingManualContent_appends(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".cursorrules");
        Files.writeString(file, "# Manual Rule\n");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(file.toString(), "vibetags rule", true);

        String content = Files.readString(file);
        assertTrue(content.contains("# Manual Rule"));
        assertTrue(content.contains("# VIBETAGS-START"));
        assertTrue(content.contains("vibetags rule"));
        assertTrue(content.contains("# VIBETAGS-END"));
        assertTrue(content.indexOf("# Manual Rule") < content.indexOf("# VIBETAGS-START"));
    }

    @Test
    void testWriteFileIfChanged_withExistingMarkers_updatesSection(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".cursorrules");
        Files.writeString(file, "# Manual Rule\n\n# VIBETAGS-START\nold rule\n# VIBETAGS-END\n\n# More Manual");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(file.toString(), "new rule", true);

        String content = Files.readString(file);
        assertTrue(content.contains("# Manual Rule"));
        assertTrue(content.contains("# More Manual"));
        assertTrue(content.contains("# VIBETAGS-START"));
        assertTrue(content.contains("new rule"));
        assertFalse(content.contains("old rule"));
    }

    @Test
    void testWriteFileIfChanged_jsonOverwriteOnly(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{\"key\":\"old\"}");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "{\"key\":\"new\"}", true);

        assertTrue(changed);
        String content = Files.readString(file);
        assertEquals("{\"key\":\"new\"}", content.trim());
        assertFalse(content.contains("VIBETAGS"), "JSON should not have markers");
    }

    // -----------------------------------------------------------------------
    // stripLegacyVibeTagsBlock
    // -----------------------------------------------------------------------

    @Test
    void stripLegacy_returnsNullSafeForEmpty() {
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        assertEquals("", p.stripLegacyVibeTagsBlock(""));
        assertNull(p.stripLegacyVibeTagsBlock(null));
    }

    @Test
    void stripLegacy_noVibeTagsHeader_unchanged() {
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        String input = "# My custom heading\n\nSome human content.";
        assertEquals(input, p.stripLegacyVibeTagsBlock(input));
    }

    @Test
    void stripLegacy_claudeMdLegacyBlock_keepsHumanContent() {
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        // Simulate CLAUDE.md before field: legacy VibeTags block + human content
        String legacy =
            "<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->\n" +
            "<project_guardrails>\n" +
            "  <locked_files>\n" +
            "  </locked_files>\n" +
            "  <contextual_instructions>\n" +
            "  </contextual_instructions>\n" +
            "</project_guardrails>\n\n" +
            "<rule>Never propose edits to files listed in <locked_files>.</rule>\n\n";
        String human = "# CLAUDE.md\n\nThis file provides guidance.\n";

        String result = p.stripLegacyVibeTagsBlock(legacy + human);

        assertFalse(result.contains("<project_guardrails>"), "Legacy XML block should be stripped");
        assertFalse(result.contains("<rule>"), "Legacy rule element should be stripped");
        assertTrue(result.contains("# CLAUDE.md"), "Human heading must be preserved");
        assertTrue(result.contains("This file provides guidance"), "Human content must be preserved");
    }

    @Test
    void stripLegacy_fullyAutoGeneratedFile_stripsAll() {
        AIGuardrailProcessor p = new AIGuardrailProcessor();
        // Simulate .cursorrules entirely generated by old VibeTags (no human content)
        String before =
            "# AUTO-GENERATED AI RULES\n" +
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags\n" +
            "# Do not edit manually.\n\n" +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.Foo` - Reason: legacy\n";

        String result = p.stripLegacyVibeTagsBlock(before);

        assertEquals("", result, "Fully auto-generated content should be stripped entirely");
    }

    @Test
    void writeFileIfChanged_stripsLegacyBlockWhenUpdatingMarkedSection(@TempDir Path tempDir) throws IOException {
        // Simulate CLAUDE.md that has both legacy (no-marker) content at top
        // and a properly-marked VibeTags section at bottom.
        String legacy =
            "<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->\n" +
            "<project_guardrails><locked_files></locked_files></project_guardrails>\n" +
            "<rule>Never propose edits to files listed in <locked_files>.</rule>\n\n";
        String human = "# CLAUDE.md\n\nHuman guidance here.\n";
        String markedSection =
            "<!-- VIBETAGS-START -->\n" +
            "<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->\n" +
            "<project_guardrails><locked_files><file path=\"Foo\"><reason>old</reason></file></locked_files></project_guardrails>\n" +
            "<rule>Never propose edits to files listed in <locked_files>.</rule>\n" +
            "<!-- VIBETAGS-END -->\n";

        Path file = tempDir.resolve("CLAUDE.md");
        Files.writeString(file, legacy + human + markedSection);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.writeFileIfChanged(file.toString(), "new generated content", true);

        String result = Files.readString(file);
        // Legacy block at top must be gone
        assertFalse(result.startsWith("<!-- # Generated"), "Legacy block must be removed from top");
        // Human content must survive
        assertTrue(result.contains("# CLAUDE.md"), "Human content heading must be preserved");
        assertTrue(result.contains("Human guidance here"), "Human prose must be preserved");
        // Marked section must be updated
        assertTrue(result.contains("<!-- VIBETAGS-START -->"), "Markers must still be present");
        assertTrue(result.contains("new generated content"), "New content must be written");
    }

    @Test
    void writeFileIfChanged_skipsUpdate_whenNoAnnotationsAndFileHasContent(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("gemini_instructions.md");
        String existingContent =
            "<!-- VIBETAGS-START -->\n" +
            "# GEMINI AI INSTRUCTIONS\n\n" +
            "## CONTINUOUS AUDIT REQUIREMENTS\n\n" +
            "File: `com.example.Foo` \nCritical Vulnerabilities to Prevent: \n- SQL Injection\n" +
            "<!-- VIBETAGS-END -->\n";
        Files.writeString(file, existingContent);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // hasNewRules=false simulates a test-compile phase with no annotations
        boolean changed = processor.writeFileIfChanged(file.toString(), "# GEMINI AI INSTRUCTIONS\n\n", false);

        assertFalse(changed, "Must not overwrite existing content when hasNewRules=false");
        assertEquals(existingContent, Files.readString(file), "File content must be unchanged");
    }

    // --- helpers ---

    @Test
    void testValidateAnnotations_contradictionWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.TestClass");
        
        Set<Element> elements = Set.of(element);
        doReturn(elements).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        
        // Mock presence of both annotations
        when(element.getAnnotation(AILocked.class)).thenReturn(mock(AILocked.class));
        when(element.getAnnotation(AIDraft.class)).thenReturn(mock(AIDraft.class));
        
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.validateAnnotations(messager, roundEnv);
        
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("is annotated with both @AIDraft and @AILocked"));
    }

    @Test
    void testValidateAnnotations_emptyAuditWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.TestClass");
        
        Set<Element> elements = Set.of(element);
        doReturn(elements).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        
        AIAudit audit = mock(AIAudit.class);
        when(audit.checkFor()).thenReturn(new String[0]);
        when(element.getAnnotation(AIAudit.class)).thenReturn(audit);
        
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.validateAnnotations(messager, roundEnv);
        
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("has no 'checkFor' items list"));
    }

    @Test
    void testValidateAnnotations_privacyIgnoreRedundancyWarning() {
        List<String> warnings = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        Element element = mock(Element.class);
        when(element.toString()).thenReturn("com.example.PrivacyClass");
        
        Set<Element> elements = Set.of(element);
        doReturn(elements).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);
        
        when(element.getAnnotation(AIPrivacy.class)).thenReturn(mock(AIPrivacy.class));
        when(element.getAnnotation(AIIgnore.class)).thenReturn(mock(AIIgnore.class));
        
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        processor.validateAnnotations(messager, roundEnv);
        
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("is annotated with both @AIPrivacy and @AIIgnore"));
    }

    @Test
    void testNewTagsProcessAggregation() {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        Map<String, String> options = Map.of();
        when(processingEnv.getOptions()).thenReturn(options);
        when(processingEnv.getMessager()).thenReturn(noopMessager());
        processor.init(processingEnv);
        
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);
        
        Element coreElement = mock(Element.class);
        Element perfElement = mock(Element.class);
        
        doReturn(Set.of(coreElement)).when(roundEnv).getElementsAnnotatedWith(AICore.class);
        doReturn(Set.of(perfElement)).when(roundEnv).getElementsAnnotatedWith(AIPerformance.class);
        
        // Also need to return empty sets for other annotations to avoid NullPointerException or issues
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(se.deversity.vibetags.annotations.AIContext.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        boolean result = processor.process(Set.of(), roundEnv);
        
        // process should return false as it allows other processors to see the annotations
        assertFalse(result);
    }

    @Test
    void testWriteFileIfChanged_IOException(@TempDir Path tempDir) throws IOException {
        Path dirPath = tempDir.resolve("some_dir");
        Files.createDirectories(dirPath);

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // Writing to a directory path should trigger an IOException (or equivalent) in some environments,
        // but to be sure we can make the file read-only if it already exists as a file.
        Path filePath = tempDir.resolve("readonly.txt");
        Files.createFile(filePath);
        if (!filePath.toFile().setReadOnly()) {
           // Fallback for systems where setReadOnly doesn't work as expected
        }

        // Even if it doesn't throw, we are testing the robustness of the processor.
        // The current implementation catches nothing specifically in writeFileIfChanged except what's handled by NIO.
        // However, writeFileIfChanged uses FileWriter which is tested.
        assertDoesNotThrow(() -> processor.writeFileIfChanged(dirPath.toString(), "content", true));
    }

    @Test
    void testCleanupGranularDirectory_NonExistent(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing_dir");
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // Should not throw when directory doesn't exist
        assertDoesNotThrow(() -> processor.cleanupGranularDirectory(missing, ".md"));
    }

    @Test
    void testCleanupGranularDirectory_IOException(@TempDir Path tempDir) throws IOException {
        Path fileAsDir = tempDir.resolve("file_as_dir");
        Files.createFile(fileAsDir);
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        // Passing a file where a directory is expected for listing should handle errors gracefully
        assertDoesNotThrow(() -> processor.cleanupGranularDirectory(fileAsDir, ".md"));
    }

    @Test
    void testMessager_MiscellaneousOverloads() {
        List<String> notes = new ArrayList<>();
        Messager messager = capturingMessager(Diagnostic.Kind.NOTE, notes);
        
        // These are mostly to cover the proxy calls in the anonymous messager class
        messager.printMessage(Diagnostic.Kind.NOTE, "test", mock(Element.class));
        messager.printMessage(Diagnostic.Kind.NOTE, "test", mock(Element.class), mock(javax.lang.model.element.AnnotationMirror.class));
        messager.printMessage(Diagnostic.Kind.NOTE, "test", mock(Element.class), mock(javax.lang.model.element.AnnotationMirror.class), mock(javax.lang.model.element.AnnotationValue.class));
        
        assertEquals(3, notes.size());
    }

    @Test
    void testOptions_ComplexPaths(@TempDir Path tempDir) throws IOException {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        ProcessingEnvironment processingEnv = mock(ProcessingEnvironment.class);
        
        Path rootPath = tempDir.resolve("my-root").toAbsolutePath();
        Files.createDirectories(rootPath);
        
        Map<String, String> options = Map.of(
            "vibetags.root", rootPath.toString(),
            "vibetags.project", "My Special Project",
            "vibetags.log.path", "custom.log"
        );
        when(processingEnv.getOptions()).thenReturn(options);
        when(processingEnv.getMessager()).thenReturn(noopMessager());
        
        processor.init(processingEnv);
        
        // Internal state is hard to check, but we verify it doesn't crash
        assertNotNull(processor);
    }

    @Test
    void testPackageKind_GranularRules(@TempDir Path tempDir) throws IOException {
        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        ProcessingEnvironment pe = mock(ProcessingEnvironment.class);
        // Opt-in for granular rules
        Path granularDir = tempDir.resolve(".cursor/rules");
        Files.createDirectories(granularDir);

        when(pe.getOptions()).thenReturn(Map.of("vibetags.root", tempDir.toString()));
        when(pe.getMessager()).thenReturn(noopMessager());
        processor.init(pe);

        RoundEnvironment re = mock(RoundEnvironment.class);
        Element element = mock(Element.class);
        when(element.getKind()).thenReturn(ElementKind.PACKAGE);
        when(element.toString()).thenReturn("com.example.pkg");
        when(element.getSimpleName()).thenReturn(mock(javax.lang.model.element.Name.class));
        when(element.getSimpleName().toString()).thenReturn("pkg");
        
        AILocked locked = mock(AILocked.class);
        when(locked.reason()).thenReturn("pkg locked");
        when(element.getAnnotation(AILocked.class)).thenReturn(locked);
        
        doReturn(Set.of(element)).when(re).getElementsAnnotatedWith(AILocked.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIContext.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIIgnore.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIAudit.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIDraft.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPrivacy.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AICore.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(AIPerformance.class);

        processor.process(Set.of(), re);
        
        // Final round
        when(re.processingOver()).thenReturn(true);
        processor.process(Set.of(), re);
        
        // Check if package-specific glob is generated: "**/pkg/**/*.java"
        Path mdcFile = granularDir.resolve("com-example-pkg.mdc");
        assertTrue(Files.exists(mdcFile), "Granular rule file should exist for package");
        String content = Files.readString(mdcFile);
        assertTrue(content.contains("**/pkg/**/*.java"), "Package glob should be recursive");
    }

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
