package se.deversity.vibetags.processor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.SupportedAnnotationTypes;
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
        assertEquals("se.deversity.vibetags.annotations.*", supportedTypes.value()[0],
            "Must use package wildcard so new annotations are auto-discovered without touching the processor");
    }

    @Test
    void testProcessorSupportsLatestSourceVersion() {
        // No fixed @SupportedSourceVersion — the processor reports SourceVersion.latestSupported()
        // via an override, so it never emits a stale "supported source version" warning on newer
        // JDKs than whatever release was hardcoded at the annotation's authoring time.
        assertNull(AIGuardrailProcessor.class.getAnnotation(javax.annotation.processing.SupportedSourceVersion.class));
        assertEquals(SourceVersion.latestSupported(), processor.getSupportedSourceVersion());
    }
}
