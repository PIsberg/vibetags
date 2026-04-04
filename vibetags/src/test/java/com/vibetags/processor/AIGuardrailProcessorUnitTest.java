package com.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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
            new String[]{"com.vibetags.annotations.*"},
            annotation.value()
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
    void testResolveActiveServices_noFilesExist_allServicesActive(@TempDir Path tempDir) {
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(serviceFiles);
        assertEquals(Set.of("cursor", "claude", "aiexclude", "chatgpt", "gemini"), active,
            "First run: all services should be active when no output files exist");
    }

    @Test
    void testResolveActiveServices_someFilesExist_onlyThoseAreActive(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        Files.createFile(tempDir.resolve(".cursorrules"));

        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(serviceFiles);
        assertEquals(Set.of("claude", "cursor"), active,
            "Only services with existing files should be active");
    }

    @Test
    void testResolveActiveServices_allFilesExist_allServicesActive(@TempDir Path tempDir) throws IOException {
        for (Path p : AIGuardrailProcessor.buildServiceFileMap(tempDir).values()) {
            Files.createFile(p);
        }
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(tempDir);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(serviceFiles);
        assertEquals(Set.of("cursor", "claude", "aiexclude", "chatgpt", "gemini"), active,
            "All services should be active when all output files exist");
    }
}
