package se.deversity.vibetags.processor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AIGuardrailProcessor annotation processor.
 * Tests processor structure and configuration.
 */
class AIGuardrailProcessorTest {

    private AIGuardrailProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AIGuardrailProcessor();
    }

    @Test
    void testProcessorCanBeInstantiated() {
        assertNotNull(processor);
    }

    @Test
    void testProcessorHasCorrectAnnotation() {
        SupportedAnnotationTypes supportedTypes = 
            AIGuardrailProcessor.class.getAnnotation(SupportedAnnotationTypes.class);
        assertNotNull(supportedTypes);
        assertEquals(1, supportedTypes.value().length);
        assertEquals("*", supportedTypes.value()[0],
            "Must use '*' so the processor runs even when all VibeTags annotations are removed");
    }

    @Test
    void testProcessorSupportsJava11() {
        SupportedSourceVersion sourceVersion = 
            AIGuardrailProcessor.class.getAnnotation(SupportedSourceVersion.class);
        assertNotNull(sourceVersion);
        assertEquals(SourceVersion.RELEASE_11, sourceVersion.value());
    }
}
