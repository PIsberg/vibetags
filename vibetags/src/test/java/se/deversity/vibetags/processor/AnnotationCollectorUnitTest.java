package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Targets the OR chain in AnnotationCollector.collect() to cover branches where
 * only a specific annotation type is present, forcing all earlier OR positions to
 * evaluate false before the target position evaluates true.
 *
 * Missing branches (JaCoCo bpc):
 *   position 5  - !draftElements.isEmpty()       (L73 true)
 *   position 7  - !coreElements.isEmpty()         (L74 true)
 *   position 12 - !immutableElements.isEmpty()    (L76 true)
 *   position 13 - !deprecatedElements.isEmpty()   (L77 true)
 *   position 15 - !regulationElements.isEmpty()   (L78 true)
 */
class AnnotationCollectorUnitTest {

    private static Element mockElem(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        return e;
    }

    /**
     * Builds a RoundEnvironment mock where ONLY the given annotation class
     * returns a non-empty set; all other annotation queries return the
     * Mockito default (empty set / null → addAll tolerates both via Set).
     */
    @SuppressWarnings("unchecked")
    private static <A extends java.lang.annotation.Annotation> RoundEnvironment reWithOnly(
            Class<A> annotationType) {
        RoundEnvironment re = mock(RoundEnvironment.class);
        doReturn(Set.of(mockElem("com.example.X")))
                .when(re).getElementsAnnotatedWith(annotationType);
        return re;
    }

    // ------------------------------------------------------------------
    // Position 5: !draftElements.isEmpty() — all positions 1-4 are false
    // ------------------------------------------------------------------

    @Test
    void collect_onlyDraftAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIDraft.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIDraft is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.draft().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 7: !coreElements.isEmpty() — positions 1-6 are false
    // ------------------------------------------------------------------

    @Test
    void collect_onlyCoreAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AICore.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AICore is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.core().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 12: !immutableElements.isEmpty() — positions 1-11 are false
    // ------------------------------------------------------------------

    @Test
    void collect_onlyImmutableAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIImmutable.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIImmutable is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.immutable().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 13: !deprecatedElements.isEmpty() — positions 1-12 are false
    // ------------------------------------------------------------------

    @Test
    void collect_onlyDeprecatedAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIDeprecated.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIDeprecated is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.deprecated().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 15: !regulationElements.isEmpty() — positions 1-14 are false
    // ------------------------------------------------------------------

    @Test
    void collect_onlyRegulationAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIRegulation.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIRegulation is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.regulation().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 2: !contextElements.isEmpty() — locked is empty, context is not
    // ------------------------------------------------------------------

    @Test
    void collect_onlyContextAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIContext.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIContext is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.context().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 4: !auditElements.isEmpty() — ignore is empty, audit is not
    // ------------------------------------------------------------------

    @Test
    void collect_onlyAuditAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIAudit.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIAudit is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.audit().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 6: !privacyElements.isEmpty() — draft is empty, privacy is not
    // ------------------------------------------------------------------

    @Test
    void collect_onlyPrivacyAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIPrivacy.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIPrivacy is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.privacy().isEmpty());
    }

    // ------------------------------------------------------------------
    // Position 8: !performanceElements.isEmpty() — positions 1-7 are false
    // ------------------------------------------------------------------

    @Test
    void collect_onlyPerformanceAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIPerformance.class);

        boolean added = collector.collect(re);

        assertTrue(added, "collect() must return true when only @AIPerformance is present");
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.performance().isEmpty());
    }

    // ------------------------------------------------------------------
    // No annotations present → added=false
    // ------------------------------------------------------------------

    @Test
    void collect_noAnnotations_returnsFalse() {
        AnnotationCollector collector = new AnnotationCollector();
        // All getElementsAnnotatedWith calls return the Mockito default (null/empty).
        // addAll(null) would NPE, so return empty set explicitly for safety.
        RoundEnvironment re = mock(RoundEnvironment.class);
        doReturn(Set.of()).when(re).getElementsAnnotatedWith(any(Class.class));

        boolean added = collector.collect(re);

        assertFalse(added, "collect() must return false when no annotations are present");
        assertFalse(collector.anyAnnotationsFound());
    }

    // ------------------------------------------------------------------
    // Position 16+: New annotations
    // ------------------------------------------------------------------

    @Test
    void collect_onlyCallersOnlyAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AICallersOnly.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.callersOnly().isEmpty());
    }

    @Test
    void collect_onlySandboxOnlyAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AISandboxOnly.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.sandboxOnly().isEmpty());
    }

    @Test
    void collect_onlyMemoryBudgetAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIMemoryBudget.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.memoryBudget().isEmpty());
    }

    @Test
    void collect_onlyPureAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIPure.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.pure().isEmpty());
    }

    @Test
    void collect_onlyDomainModelAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIDomainModel.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.domainModel().isEmpty());
    }

    @Test
    void collect_onlyExtensibleAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIExtensible.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.extensible().isEmpty());
    }

    @Test
    void collect_onlyInputSanitizedAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIInputSanitized.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.inputSanitized().isEmpty());
    }

    @Test
    void collect_onlySecureLoggingAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AISecureLogging.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.secureLogging().isEmpty());
    }

    @Test
    void collect_onlyExplainAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIExplain.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.explain().isEmpty());
    }

    @Test
    void collect_onlyPrototypeAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AIPrototype.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.prototype().isEmpty());
    }

    @Test
    void collect_onlySunsetAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AISunset.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.sunset().isEmpty());
    }

    @Test
    void collect_onlyTemporaryAnnotation_returnsTrue() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = reWithOnly(AITemporary.class);
        boolean added = collector.collect(re);
        assertTrue(added);
        assertTrue(collector.anyAnnotationsFound());
        assertFalse(collector.temporary().isEmpty());
    }

    @Test
    void reset_clearsNewAnnotations() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re1 = mock(RoundEnvironment.class);
        doReturn(Set.of(mockElem("com.example.C1"))).when(re1).getElementsAnnotatedWith(AICallersOnly.class);
        doReturn(Set.of(mockElem("com.example.C2"))).when(re1).getElementsAnnotatedWith(AISandboxOnly.class);
        doReturn(Set.of(mockElem("com.example.C3"))).when(re1).getElementsAnnotatedWith(AIMemoryBudget.class);
        doReturn(Set.of(mockElem("com.example.C4"))).when(re1).getElementsAnnotatedWith(AIPure.class);
        doReturn(Set.of(mockElem("com.example.C5"))).when(re1).getElementsAnnotatedWith(AIDomainModel.class);
        doReturn(Set.of(mockElem("com.example.C6"))).when(re1).getElementsAnnotatedWith(AIExtensible.class);
        doReturn(Set.of(mockElem("com.example.C7"))).when(re1).getElementsAnnotatedWith(AIInputSanitized.class);
        doReturn(Set.of(mockElem("com.example.C8"))).when(re1).getElementsAnnotatedWith(AISecureLogging.class);
        doReturn(Set.of(mockElem("com.example.C9"))).when(re1).getElementsAnnotatedWith(AIExplain.class);
        doReturn(Set.of(mockElem("com.example.C10"))).when(re1).getElementsAnnotatedWith(AIPrototype.class);
        doReturn(Set.of(mockElem("com.example.C11"))).when(re1).getElementsAnnotatedWith(AISunset.class);
        doReturn(Set.of(mockElem("com.example.C12"))).when(re1).getElementsAnnotatedWith(AITemporary.class);

        assertTrue(collector.collect(re1));
        assertFalse(collector.callersOnly().isEmpty());
        assertFalse(collector.sandboxOnly().isEmpty());
        assertFalse(collector.memoryBudget().isEmpty());
        assertFalse(collector.pure().isEmpty());
        assertFalse(collector.domainModel().isEmpty());
        assertFalse(collector.extensible().isEmpty());
        assertFalse(collector.inputSanitized().isEmpty());
        assertFalse(collector.secureLogging().isEmpty());
        assertFalse(collector.explain().isEmpty());
        assertFalse(collector.prototype().isEmpty());
        assertFalse(collector.sunset().isEmpty());
        assertFalse(collector.temporary().isEmpty());

        collector.reset();

        assertTrue(collector.callersOnly().isEmpty());
        assertTrue(collector.sandboxOnly().isEmpty());
        assertTrue(collector.memoryBudget().isEmpty());
        assertTrue(collector.pure().isEmpty());
        assertTrue(collector.domainModel().isEmpty());
        assertTrue(collector.extensible().isEmpty());
        assertTrue(collector.inputSanitized().isEmpty());
        assertTrue(collector.secureLogging().isEmpty());
        assertTrue(collector.explain().isEmpty());
        assertTrue(collector.prototype().isEmpty());
        assertTrue(collector.sunset().isEmpty());
        assertTrue(collector.temporary().isEmpty());
        assertFalse(collector.anyAnnotationsFound());
    }
}
