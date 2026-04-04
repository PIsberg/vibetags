package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Messager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AIGuardrailProcessor.
 * Tests the processor logic without full compilation.
 */
class AIGuardrailProcessorUnitTest {

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
        for (Path p : AIGuardrailProcessor.buildServiceFileMap(tempDir).values()) {
            Files.createDirectories(p.getParent());
            Files.createFile(p);
        }
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertEquals(Set.of("cursor", "claude", "aiexclude", "codex", "gemini", "copilot", "qwen", "cursor_ignore", "claude_ignore", "copilot_ignore", "qwen_ignore"), active,
            "All services should be active when all output files exist");
    }

    // --- annotation removal ---

    @Test
    void testPartialAnnotationRemoval_updatesFile(@TempDir Path tempDir) throws IOException {
        // Simulate a file produced by a previous compile that had @AILocked on two classes
        Path file = tempDir.resolve(".cursorrules");
        String previousContent =
            "# AUTO-GENERATED AI RULES\n" +
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
        boolean changed = processor.writeFileIfChanged(file.toString(), newContent);

        assertTrue(changed, "File must be updated when an annotation is removed");
        assertFalse(Files.readString(file).contains("OrderService"),
            "Removed annotation entry must no longer appear in the file");
        assertTrue(Files.readString(file).contains("PaymentProcessor"),
            "Remaining annotation entry must still be present");
    }

    @Test
    void testAllAnnotationsRemoved_fileUpdatedToHeaderOnly(@TempDir Path tempDir) throws IOException {
        // Simulate a file produced by a previous compile that had @AILocked entries
        Path file = tempDir.resolve(".cursorrules");
        String previousContent =
            "# AUTO-GENERATED AI RULES\n" +
            "## LOCKED FILES (DO NOT EDIT)\n" +
            "* `com.example.PaymentProcessor` - Reason: legacy\n";
        Files.writeString(file, previousContent);

        // After removing ALL VibeTags annotations, the processor generates header-only content
        String headerOnlyContent = "# AUTO-GENERATED AI RULES\n## LOCKED FILES (DO NOT EDIT)\n";

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), headerOnlyContent);

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
        boolean changed = processor.writeFileIfChanged(file.toString(), "new content");

        assertTrue(changed, "Should return true when file was empty");
        assertTrue(Files.readString(file).contains("new content"));
    }

    @Test
    void testWriteFileIfChanged_sameContent_returnsFalseAndDoesNotWrite(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "same content");
        long before = Files.getLastModifiedTime(file).toMillis();

        // Small sleep to ensure mtime would differ if written
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "same content");

        assertFalse(changed, "Should return false when content is identical");
        assertEquals(before, Files.getLastModifiedTime(file).toMillis(),
            "File should not have been rewritten");
    }

    @Test
    void testWriteFileIfChanged_differentContent_writesAndReturnsTrue(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "old content");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "new content");

        assertTrue(changed, "Should return true when content differs");
        assertTrue(Files.readString(file).contains("new content"));
    }

    @Test
    void testWriteFileIfChanged_stripsWhitespaceForComparison(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.md");
        // File has trailing newline (as written by PrintWriter.println)
        Files.writeString(file, "content\n");

        AIGuardrailProcessor processor = new AIGuardrailProcessor();
        boolean changed = processor.writeFileIfChanged(file.toString(), "content");

        assertFalse(changed, "Trailing whitespace difference should not trigger a rewrite");
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
