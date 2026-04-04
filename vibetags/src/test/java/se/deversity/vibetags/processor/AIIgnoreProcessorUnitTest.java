package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for @AIIgnore processing logic in AIGuardrailProcessor.
 *
 * Full end-to-end output validation (generated file content) is covered by
 * AnnotationProcessorEndToEndTest. These tests focus on the processor's
 * structural behaviour and the opt-in mechanism as it relates to @AIIgnore.
 */
class AIIgnoreProcessorUnitTest {

    // --- annotation definition ---

    @Test
    void testAIIgnoreAnnotationIsOnClasspath() {
        assertDoesNotThrow(() ->
            Class.forName("se.deversity.vibetags.annotations.AIIgnore"),
            "@AIIgnore must be on the processor classpath"
        );
    }

    @Test
    void testAIIgnoreHasSourceRetention() throws Exception {
        Class<?> aiIgnore = Class.forName("se.deversity.vibetags.annotations.AIIgnore");
        java.lang.annotation.Retention retention =
            aiIgnore.getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention, "@AIIgnore must declare @Retention");
        assertEquals(
            java.lang.annotation.RetentionPolicy.SOURCE,
            retention.value(),
            "@AIIgnore must use SOURCE retention so it has zero runtime footprint"
        );
    }

    @Test
    void testAIIgnoreHasReasonAttribute() throws Exception {
        Class<?> aiIgnore = Class.forName("se.deversity.vibetags.annotations.AIIgnore");
        java.lang.reflect.Method reason = aiIgnore.getDeclaredMethod("reason");
        assertNotNull(reason.getDefaultValue(), "reason() must have a default value");
        assertFalse(((String) reason.getDefaultValue()).isEmpty(),
            "default reason must not be blank");
    }

    @Test
    void testAIIgnoreTargetsTypeMethodAndField() throws Exception {
        Class<?> aiIgnore = Class.forName("se.deversity.vibetags.annotations.AIIgnore");
        java.lang.annotation.Target target =
            aiIgnore.getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target, "@AIIgnore must declare @Target");
        java.lang.annotation.ElementType[] targets = target.value();
        assertArrayEquals(
            new java.lang.annotation.ElementType[]{
                java.lang.annotation.ElementType.TYPE,
                java.lang.annotation.ElementType.METHOD,
                java.lang.annotation.ElementType.FIELD
            },
            targets,
            "@AIIgnore must target TYPE, METHOD, and FIELD"
        );
    }

    // --- opt-in mechanism (service file resolution) ---

    @Test
    void testIgnoreOutputIsGatedByServiceFilePresence(@TempDir Path tempDir) throws IOException {
        // When no service files exist the active set is empty — @AIIgnore output is never written
        java.util.Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        java.util.Set<String> active =
            AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertTrue(active.isEmpty(),
            "No service files → active set must be empty, so @AIIgnore content is not written");
    }

    @Test
    void testIgnoreOutputIsWrittenWhenClaudeMdExists(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        java.util.Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        java.util.Set<String> active =
            AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertTrue(active.contains("claude"),
            "claude service must be active when CLAUDE.md exists");
    }

    @Test
    void testIgnoreOutputIsWrittenWhenCursorRulesExists(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve(".cursorrules"));
        java.util.Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        java.util.Set<String> active =
            AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertTrue(active.contains("cursor"),
            "cursor service must be active when .cursorrules exists");
    }

    @Test
    void testIgnoreOutputIsWrittenWhenCopilotInstructionsExists(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve(".github"));
        Files.createFile(tempDir.resolve(".github/copilot-instructions.md"));
        java.util.Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        java.util.Set<String> active =
            AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        assertTrue(active.contains("copilot"),
            "copilot service must be active when .github/copilot-instructions.md exists");
    }

    // --- helper ---

    private static Messager noopMessager() {
        return new Messager() {
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg,
                    javax.lang.model.element.Element e) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg,
                    javax.lang.model.element.Element e,
                    javax.lang.model.element.AnnotationMirror a) {}
            public void printMessage(Diagnostic.Kind kind, CharSequence msg,
                    javax.lang.model.element.Element e,
                    javax.lang.model.element.AnnotationMirror a,
                    javax.lang.model.element.AnnotationValue v) {}
        };
    }
}
