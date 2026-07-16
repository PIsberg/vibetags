package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.BuildFingerprint;
import se.deversity.vibetags.processor.internal.ProcessorVersion;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BuildFingerprint} targeting the {@code a == null} defensive
 * null-annotation branches inside each per-annotation lambda.
 *
 * <p>In theory, an element in a collected set should always have the corresponding
 * annotation accessible during annotation processing. In practice the branches exist
 * as a defensive fallback for any scenario (mocked tests, custom processors) where
 * {@code getAnnotation()} returns {@code null}. These tests verify that the fingerprint
 * computation degrades gracefully to an empty string rather than throwing NPE.
 */
class BuildFingerprintUnitTest {

    /**
     * Single test that covers all 14 null-annotation branches in one pass.
     * A mock element is added to each annotation set but returns {@code null}
     * for the corresponding annotation method — exercising every {@code a == null}
     * guard in {@link BuildFingerprint#compute}.
     */
    @Test
    void compute_nullAnnotationsOnAllTypes_returnsValidFingerprint() {
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment roundEnv = mock(RoundEnvironment.class);

        // AILocked — null annotation
        Element lockedElem = namedElement("com.example.Locked");
        when(lockedElem.getAnnotation(AILocked.class)).thenReturn(null);
        doReturn(Set.of(lockedElem)).when(roundEnv).getElementsAnnotatedWith(AILocked.class);

        // AIContext — null annotation
        Element contextElem = namedElement("com.example.Ctx");
        when(contextElem.getAnnotation(AIContext.class)).thenReturn(null);
        doReturn(Set.of(contextElem)).when(roundEnv).getElementsAnnotatedWith(AIContext.class);

        // AIAudit — null annotation
        Element auditElem = namedElement("com.example.Audit");
        when(auditElem.getAnnotation(AIAudit.class)).thenReturn(null);
        doReturn(Set.of(auditElem)).when(roundEnv).getElementsAnnotatedWith(AIAudit.class);

        // AIDraft — null annotation
        Element draftElem = namedElement("com.example.Draft");
        when(draftElem.getAnnotation(AIDraft.class)).thenReturn(null);
        doReturn(Set.of(draftElem)).when(roundEnv).getElementsAnnotatedWith(AIDraft.class);

        // AIPrivacy — null annotation
        Element privacyElem = namedElement("com.example.Privacy");
        when(privacyElem.getAnnotation(AIPrivacy.class)).thenReturn(null);
        doReturn(Set.of(privacyElem)).when(roundEnv).getElementsAnnotatedWith(AIPrivacy.class);

        // AICore — null annotation
        Element coreElem = namedElement("com.example.Core");
        when(coreElem.getAnnotation(AICore.class)).thenReturn(null);
        doReturn(Set.of(coreElem)).when(roundEnv).getElementsAnnotatedWith(AICore.class);

        // AIPerformance — null annotation
        Element perfElem = namedElement("com.example.Perf");
        when(perfElem.getAnnotation(AIPerformance.class)).thenReturn(null);
        doReturn(Set.of(perfElem)).when(roundEnv).getElementsAnnotatedWith(AIPerformance.class);

        // AIContract — null annotation
        Element contractElem = namedElement("com.example.Contract");
        when(contractElem.getAnnotation(AIContract.class)).thenReturn(null);
        doReturn(Set.of(contractElem)).when(roundEnv).getElementsAnnotatedWith(AIContract.class);

        // AITestDriven — null annotation
        Element testDrivenElem = namedElement("com.example.TestDriven");
        when(testDrivenElem.getAnnotation(AITestDriven.class)).thenReturn(null);
        doReturn(Set.of(testDrivenElem)).when(roundEnv).getElementsAnnotatedWith(AITestDriven.class);

        // AIThreadSafe — null annotation
        Element threadSafeElem = namedElement("com.example.ThreadSafe");
        when(threadSafeElem.getAnnotation(AIThreadSafe.class)).thenReturn(null);
        doReturn(Set.of(threadSafeElem)).when(roundEnv).getElementsAnnotatedWith(AIThreadSafe.class);

        // AIImmutable — null annotation
        Element immutableElem = namedElement("com.example.Immutable");
        when(immutableElem.getAnnotation(AIImmutable.class)).thenReturn(null);
        doReturn(Set.of(immutableElem)).when(roundEnv).getElementsAnnotatedWith(AIImmutable.class);

        // AIDeprecated — null annotation
        Element deprecatedElem = namedElement("com.example.Deprecated");
        when(deprecatedElem.getAnnotation(AIDeprecated.class)).thenReturn(null);
        doReturn(Set.of(deprecatedElem)).when(roundEnv).getElementsAnnotatedWith(AIDeprecated.class);

        // AIObservability — null annotation
        Element obsElem = namedElement("com.example.Obs");
        when(obsElem.getAnnotation(AIObservability.class)).thenReturn(null);
        doReturn(Set.of(obsElem)).when(roundEnv).getElementsAnnotatedWith(AIObservability.class);

        // AIRegulation — null annotation
        Element regElem = namedElement("com.example.Reg");
        when(regElem.getAnnotation(AIRegulation.class)).thenReturn(null);
        doReturn(Set.of(regElem)).when(roundEnv).getElementsAnnotatedWith(AIRegulation.class);

        collector.collect(roundEnv);

        // compute must not throw even though every annotation returns null
        String fp = assertDoesNotThrow(
            () -> BuildFingerprint.compute(collector, Set.of("cursor", "claude")),
            "compute must handle null annotations without throwing NPE");

        assertNotNull(fp, "Fingerprint must not be null");
        assertEquals(8, fp.length(), "Fingerprint must be exactly 8 hex chars");
        assertTrue(fp.matches("[0-9a-f]{8}"), "Fingerprint must be lowercase hex: " + fp);
    }

    @Test
    void compute_nullAnnotations_producesStableResult() {
        // Verify determinism: same null-annotation inputs → same fingerprint across calls.
        AnnotationCollector c1 = new AnnotationCollector();
        AnnotationCollector c2 = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element lockedElem = namedElement("com.example.Locked");
        when(lockedElem.getAnnotation(AILocked.class)).thenReturn(null);
        doReturn(Set.of(lockedElem)).when(re).getElementsAnnotatedWith(AILocked.class);

        c1.collect(re);
        c2.collect(re);

        assertEquals(
            BuildFingerprint.compute(c1, Set.of("cursor")),
            BuildFingerprint.compute(c2, Set.of("cursor")),
            "Identical null-annotation inputs must produce the same fingerprint");
    }

    @Test
    void compute_processorVersionChange_invalidatesFingerprint() {
        // A new processor release may render different content from identical annotation
        // inputs, so the version alone must change the fingerprint — otherwise the
        // short-circuit would skip regeneration after an upgrade and leave stale output.
        AnnotationCollector collector = new AnnotationCollector();
        RoundEnvironment re = mock(RoundEnvironment.class);

        Element lockedElem = namedElement("com.example.Locked");
        when(lockedElem.getAnnotation(AILocked.class)).thenReturn(null);
        doReturn(Set.of(lockedElem)).when(re).getElementsAnnotatedWith(AILocked.class);
        collector.collect(re);

        String v1 = BuildFingerprint.compute(collector, Set.of("cursor"), "1.0.0");
        String v2 = BuildFingerprint.compute(collector, Set.of("cursor"), "1.0.1");

        assertNotEquals(v1, v2,
            "Identical inputs under different processor versions must produce different fingerprints");
        assertEquals(v1, BuildFingerprint.compute(collector, Set.of("cursor"), "1.0.0"),
            "Same version + same inputs must stay deterministic");
    }

    @Test
    void compute_defaultOverload_usesRunningProcessorVersion() {
        AnnotationCollector collector = new AnnotationCollector();

        assertEquals(
            BuildFingerprint.compute(collector, Set.of("cursor"), ProcessorVersion.get()),
            BuildFingerprint.compute(collector, Set.of("cursor")),
            "The two-arg overload must fingerprint with the running processor's version");
    }

    @Test
    void processorVersion_isNeverBlank() {
        String v = ProcessorVersion.get();
        assertNotNull(v, "ProcessorVersion.get() must never return null");
        assertFalse(v.isBlank(), "ProcessorVersion.get() must never return blank");
    }

    /**
     * Every collected annotation bucket must be part of the fingerprint input. If a bucket is
     * omitted, adding/removing that annotation leaves the fingerprint unchanged, and the top-level
     * short-circuit in {@code generateFiles()} skips regeneration — the generated guardrail files
     * silently go stale. This exercises the 12 newest buckets (v1.1 annotations), which are
     * rendered by every renderer (LlmsRenderer, ClaudeRenderer, GranularRenderer, ...) and must
     * therefore invalidate the cache like the original 27.
     */
    @Test
    void compute_everyNewestAnnotationBucket_affectsFingerprint() {
        String emptyFp = BuildFingerprint.compute(new AnnotationCollector(), Set.of("cursor"), "1.0.0");

        java.util.Map<String, Class<? extends java.lang.annotation.Annotation>> buckets =
            new java.util.LinkedHashMap<>();
        buckets.put("callersOnly",    se.deversity.vibetags.annotations.AICallersOnly.class);
        buckets.put("sandboxOnly",    se.deversity.vibetags.annotations.AISandboxOnly.class);
        buckets.put("memoryBudget",   se.deversity.vibetags.annotations.AIMemoryBudget.class);
        buckets.put("pure",           se.deversity.vibetags.annotations.AIPure.class);
        buckets.put("domainModel",    se.deversity.vibetags.annotations.AIDomainModel.class);
        buckets.put("extensible",     se.deversity.vibetags.annotations.AIExtensible.class);
        buckets.put("inputSanitized", se.deversity.vibetags.annotations.AIInputSanitized.class);
        buckets.put("secureLogging",  se.deversity.vibetags.annotations.AISecureLogging.class);
        buckets.put("explain",        se.deversity.vibetags.annotations.AIExplain.class);
        buckets.put("prototype",      se.deversity.vibetags.annotations.AIPrototype.class);
        buckets.put("sunset",         se.deversity.vibetags.annotations.AISunset.class);
        buckets.put("temporary",      se.deversity.vibetags.annotations.AITemporary.class);

        for (var entry : buckets.entrySet()) {
            AnnotationCollector collector = new AnnotationCollector();
            RoundEnvironment re = mock(RoundEnvironment.class);
            Element elem = namedElement("com.example." + entry.getKey());
            doReturn(Set.of(elem)).when(re).getElementsAnnotatedWith(entry.getValue());
            collector.collect(re);

            assertNotEquals(emptyFp,
                BuildFingerprint.compute(collector, Set.of("cursor"), "1.0.0"),
                "An element in the '" + entry.getKey() + "' bucket must change the fingerprint "
                    + "(otherwise the short-circuit skips regeneration and output goes stale)");
        }
    }

    /**
     * Attribute values of the newest buckets must also be fingerprinted: editing
     * {@code @AITemporary(expiresOn = ...)} on an already-annotated element changes the rendered
     * output, so it must change the fingerprint too.
     */
    @Test
    void compute_temporaryAttributeChange_invalidatesFingerprint() {
        String fpA = fingerprintWithTemporary("2026-01-01", "hotfix A");
        String fpB = fingerprintWithTemporary("2027-12-31", "hotfix A");

        assertNotEquals(fpA, fpB,
            "Changing @AITemporary.expiresOn must change the fingerprint — the rendered "
                + "expiration date differs, so regeneration must not be skipped");
        assertEquals(fpA, fingerprintWithTemporary("2026-01-01", "hotfix A"),
            "Identical @AITemporary attributes must stay deterministic");
    }

    private static String fingerprintWithTemporary(String expiresOn, String reason) {
        se.deversity.vibetags.annotations.AITemporary ann =
            mock(se.deversity.vibetags.annotations.AITemporary.class);
        when(ann.expiresOn()).thenReturn(expiresOn);
        when(ann.reason()).thenReturn(reason);

        Element elem = namedElement("com.example.TempFix");
        when(elem.getAnnotation(se.deversity.vibetags.annotations.AITemporary.class)).thenReturn(ann);

        RoundEnvironment re = mock(RoundEnvironment.class);
        doReturn(Set.of(elem)).when(re).getElementsAnnotatedWith(se.deversity.vibetags.annotations.AITemporary.class);

        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(re);
        return BuildFingerprint.compute(collector, Set.of("cursor"), "1.0.0");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /** Creates a mock Element whose toString() returns the given FQN. */
    private static Element namedElement(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        return e;
    }
}
