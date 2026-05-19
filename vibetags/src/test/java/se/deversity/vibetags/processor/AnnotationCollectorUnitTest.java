package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
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
}
