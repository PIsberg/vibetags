package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.*;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.BuildFingerprint;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mutation-hardening for {@link BuildFingerprint}. The fingerprint is the correctness contract of
 * the {@code .vibetags-cache} short-circuit: if it fails to change when the annotations change, the
 * processor silently skips regeneration and emits stale output. PIT survivors here were exactly
 * that failure mode — "removed call to {@code appendAnnotationSet}" (a whole annotation bucket
 * stops contributing) and "lambda replaced return value with \"\"" (an attribute stops
 * contributing). Each assertion below pins one of those contributions:
 *
 * <ul>
 *   <li>attribute-bearing annotations: two elements differing only in one attribute must hash
 *       differently (kills the lambda-return mutant and the removed-bucket mutant together);</li>
 *   <li>marker annotations: presence vs absence must hash differently (kills the removed-bucket
 *       mutant — markers have no attribute, so the "" lambda is intentional/equivalent);</li>
 *   <li>element order must not matter (kills the {@code List::sort} removal in appendAnnotationSet).</li>
 * </ul>
 */
class BuildFingerprintMutationTest {

    private static Element mockEl(String fqn) {
        Element e = mock(Element.class);
        when(e.toString()).thenReturn(fqn);
        when(e.getKind()).thenReturn(ElementKind.CLASS);
        Name nm = mock(Name.class);
        when(nm.toString()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
        when(e.getSimpleName()).thenReturn(nm);
        return e;
    }

    private static String fp(AnnotationCollector collector) {
        return BuildFingerprint.compute(collector, Set.of());
    }

    private static <A extends Annotation> AnnotationCollector collectorOf(Class<A> type, String fqn, A ann) {
        RoundEnvironment re = mock(RoundEnvironment.class);
        Element el = mockEl(fqn);
        when(el.getAnnotation(type)).thenReturn(ann);
        doReturn(Set.of(el)).when(re).getElementsAnnotatedWith(type);
        AnnotationCollector collector = new AnnotationCollector();
        collector.collect(re);
        return collector;
    }

    /** Asserts that a single attribute of {@code type} feeds into the fingerprint. */
    private static <A extends Annotation> void assertAttrMatters(
            Class<A> type, String fqn, Consumer<A> variantA, Consumer<A> variantB, String msg) {
        A a1 = mock(type);
        variantA.accept(a1);
        A a2 = mock(type);
        variantB.accept(a2);
        assertNotEquals(fp(collectorOf(type, fqn, a1)), fp(collectorOf(type, fqn, a2)), msg);
    }

    private static final String EMPTY_FP = fp(new AnnotationCollector());

    private static <A extends Annotation> void assertPresenceMatters(Class<A> type, String fqn, String msg) {
        assertNotEquals(EMPTY_FP, fp(collectorOf(type, fqn, mock(type))), msg);
    }

    @Test
    void stringAttributesFeedTheFingerprint() {
        assertAttrMatters(AILocked.class, "com.ex.L",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AILocked.reason must affect the fingerprint");
        assertAttrMatters(AIContext.class, "com.ex.C",
            a -> { when(a.focus()).thenReturn("A"); when(a.avoids()).thenReturn("x"); },
            a -> { when(a.focus()).thenReturn("B"); when(a.avoids()).thenReturn("x"); },
            "AIContext.focus must affect the fingerprint");
        assertAttrMatters(AIDraft.class, "com.ex.D",
            a -> when(a.instructions()).thenReturn("A"), a -> when(a.instructions()).thenReturn("B"),
            "AIDraft.instructions must affect the fingerprint");
        assertAttrMatters(AIPrivacy.class, "com.ex.P",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AIPrivacy.reason must affect the fingerprint");
        assertAttrMatters(AICore.class, "com.ex.K",
            a -> { when(a.sensitivity()).thenReturn("A"); when(a.note()).thenReturn("n"); },
            a -> { when(a.sensitivity()).thenReturn("B"); when(a.note()).thenReturn("n"); },
            "AICore.sensitivity must affect the fingerprint");
        assertAttrMatters(AIPerformance.class, "com.ex.F",
            a -> when(a.constraint()).thenReturn("A"), a -> when(a.constraint()).thenReturn("B"),
            "AIPerformance.constraint must affect the fingerprint");
        assertAttrMatters(AIContract.class, "com.ex.T",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AIContract.reason must affect the fingerprint");
        assertAttrMatters(AIThreadSafe.class, "com.ex.TS",
            a -> { when(a.strategy()).thenReturn(AIThreadSafe.Strategy.LOCK_FREE); when(a.note()).thenReturn("n"); },
            a -> { when(a.strategy()).thenReturn(AIThreadSafe.Strategy.SYNCHRONIZED); when(a.note()).thenReturn("n"); },
            "AIThreadSafe.strategy must affect the fingerprint");
        assertAttrMatters(AIImmutable.class, "com.ex.IM",
            a -> when(a.note()).thenReturn("A"), a -> when(a.note()).thenReturn("B"),
            "AIImmutable.note must affect the fingerprint");
        assertAttrMatters(AIDeprecated.class, "com.ex.DP",
            a -> { when(a.replacedBy()).thenReturn("A"); when(a.migrationGuide()).thenReturn("m"); when(a.deadline()).thenReturn("d"); },
            a -> { when(a.replacedBy()).thenReturn("B"); when(a.migrationGuide()).thenReturn("m"); when(a.deadline()).thenReturn("d"); },
            "AIDeprecated.replacedBy must affect the fingerprint");
        assertAttrMatters(AIRegulation.class, "com.ex.RG",
            a -> { when(a.standard()).thenReturn("A"); when(a.clause()).thenReturn("c"); when(a.description()).thenReturn("d"); },
            a -> { when(a.standard()).thenReturn("B"); when(a.clause()).thenReturn("c"); when(a.description()).thenReturn("d"); },
            "AIRegulation.standard must affect the fingerprint");
        assertAttrMatters(AIIdempotent.class, "com.ex.ID",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AIIdempotent.reason must affect the fingerprint");
        assertAttrMatters(AISecure.class, "com.ex.SEC",
            a -> when(a.aspect()).thenReturn("A"), a -> when(a.aspect()).thenReturn("B"),
            "AISecure.aspect must affect the fingerprint");
        assertAttrMatters(AISandboxOnly.class, "com.ex.SO",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AISandboxOnly.reason must affect the fingerprint");
        assertAttrMatters(AIPure.class, "com.ex.PU",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AIPure.reason must affect the fingerprint");
        assertAttrMatters(AIPrototype.class, "com.ex.PR",
            a -> when(a.reason()).thenReturn("A"), a -> when(a.reason()).thenReturn("B"),
            "AIPrototype.reason must affect the fingerprint");
        assertAttrMatters(AITemporary.class, "com.ex.TM",
            a -> { when(a.expiresOn()).thenReturn("A"); when(a.reason()).thenReturn("r"); },
            a -> { when(a.expiresOn()).thenReturn("B"); when(a.reason()).thenReturn("r"); },
            "AITemporary.expiresOn must affect the fingerprint");
    }

    @Test
    void arrayAndEnumAttributesFeedTheFingerprint() {
        assertAttrMatters(AIAudit.class, "com.ex.A",
            a -> when(a.checkFor()).thenReturn(new String[]{"A"}),
            a -> when(a.checkFor()).thenReturn(new String[]{"B"}),
            "AIAudit.checkFor must affect the fingerprint");
        assertAttrMatters(AITestDriven.class, "com.ex.TD",
            a -> stubTestDriven(a, 90),
            a -> stubTestDriven(a, 91),
            "AITestDriven.coverageGoal must affect the fingerprint");
        assertAttrMatters(AIObservability.class, "com.ex.OB",
            a -> stubObservability(a, "A"),
            a -> stubObservability(a, "B"),
            "AIObservability.metrics must affect the fingerprint");
        assertAttrMatters(AIArchitecture.class, "com.ex.AR",
            a -> { when(a.belongsTo()).thenReturn("A"); when(a.cannotReference()).thenReturn(new String[]{"x"}); },
            a -> { when(a.belongsTo()).thenReturn("B"); when(a.cannotReference()).thenReturn(new String[]{"x"}); },
            "AIArchitecture.belongsTo must affect the fingerprint");
        assertAttrMatters(AIFeatureFlag.class, "com.ex.FF",
            a -> { when(a.flag()).thenReturn("A"); when(a.defaultValue()).thenReturn(true); },
            a -> { when(a.flag()).thenReturn("B"); when(a.defaultValue()).thenReturn(true); },
            "AIFeatureFlag.flag must affect the fingerprint");
        assertAttrMatters(AICallersOnly.class, "com.ex.CO",
            a -> when(a.value()).thenReturn(new String[]{"A"}),
            a -> when(a.value()).thenReturn(new String[]{"B"}),
            "AICallersOnly.value must affect the fingerprint");
        assertAttrMatters(AIMemoryBudget.class, "com.ex.MB",
            a -> when(a.value()).thenReturn(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION),
            a -> when(a.value()).thenReturn(AIMemoryBudget.AllocationPolicy.NO_AUTOBOXING),
            "AIMemoryBudget.value must affect the fingerprint");
        assertAttrMatters(AIDomainModel.class, "com.ex.DM",
            a -> when(a.allow()).thenReturn(new String[]{"A"}),
            a -> when(a.allow()).thenReturn(new String[]{"B"}),
            "AIDomainModel.allow must affect the fingerprint");
        assertAttrMatters(AIExtensible.class, "com.ex.EX",
            a -> when(a.value()).thenReturn(AIExtensible.Strategy.STRATEGY_PATTERN),
            a -> when(a.value()).thenReturn(AIExtensible.Strategy.VISITOR_PATTERN),
            "AIExtensible.value must affect the fingerprint");
        assertAttrMatters(AIInputSanitized.class, "com.ex.IZ",
            a -> when(a.value()).thenReturn(new AIInputSanitized.SanitizerType[]{AIInputSanitized.SanitizerType.SQL_INJECTION}),
            a -> when(a.value()).thenReturn(new AIInputSanitized.SanitizerType[]{AIInputSanitized.SanitizerType.XSS}),
            "AIInputSanitized.value must affect the fingerprint");
        assertAttrMatters(AISecureLogging.class, "com.ex.SL",
            a -> when(a.value()).thenReturn(AISecureLogging.MaskingPolicy.OMIT),
            a -> when(a.value()).thenReturn(AISecureLogging.MaskingPolicy.HASH),
            "AISecureLogging.value must affect the fingerprint");
        assertAttrMatters(AIExplain.class, "com.ex.XP",
            a -> when(a.value()).thenReturn(AIExplain.ComplexityLevel.HIGH),
            a -> when(a.value()).thenReturn(AIExplain.ComplexityLevel.LOW),
            "AIExplain.value must affect the fingerprint");
        assertAttrMatters(AISunset.class, "com.ex.SN",
            a -> { when(a.jira()).thenReturn("A"); doReturn(Object.class).when(a).replacement(); },
            a -> { when(a.jira()).thenReturn("B"); doReturn(Object.class).when(a).replacement(); },
            "AISunset.jira must affect the fingerprint");
    }

    private static void stubTestDriven(AITestDriven a, int coverageGoal) {
        when(a.coverageGoal()).thenReturn(coverageGoal);
        when(a.testLocation()).thenReturn("t");
        when(a.mockPolicy()).thenReturn("m");
        when(a.framework()).thenReturn(new AITestDriven.Framework[]{AITestDriven.Framework.JUNIT_5});
    }

    private static void stubObservability(AIObservability a, String metric) {
        when(a.metrics()).thenReturn(new String[]{metric});
        when(a.traces()).thenReturn(new String[]{});
        when(a.logs()).thenReturn(new String[]{});
        when(a.note()).thenReturn("n");
    }

    @Test
    void markerAnnotationPresenceFeedsTheFingerprint() {
        assertPresenceMatters(AIIgnore.class, "com.ex.Ig", "AIIgnore presence must affect the fingerprint");
        assertPresenceMatters(AIParallelTests.class, "com.ex.Pt", "AIParallelTests presence must affect the fingerprint");
        assertPresenceMatters(AILegacyBridge.class, "com.ex.Lb", "AILegacyBridge presence must affect the fingerprint");
        assertPresenceMatters(AIPublicAPI.class, "com.ex.Pa", "AIPublicAPI presence must affect the fingerprint");
        assertPresenceMatters(AIStrictExceptions.class, "com.ex.Se", "AIStrictExceptions presence must affect the fingerprint");
        assertPresenceMatters(AIStrictTypes.class, "com.ex.St", "AIStrictTypes presence must affect the fingerprint");
        assertPresenceMatters(AIInternationalized.class, "com.ex.It", "AIInternationalized presence must affect the fingerprint");
        assertPresenceMatters(AIStrictClasspath.class, "com.ex.Sc", "AIStrictClasspath presence must affect the fingerprint");
        assertPresenceMatters(AISchemaSafe.class, "com.ex.Ss", "AISchemaSafe presence must affect the fingerprint");
    }

    @Test
    void elementOrderDoesNotAlterFingerprint() {
        // appendAnnotationSet sorts by element path so iteration order can't drift the hash.
        // Removing that sort makes these two insertion orders hash differently.
        Element apple = mockEl("com.ex.Apple");
        Element zebra = mockEl("com.ex.Zebra");
        AILocked ann = mock(AILocked.class);
        when(ann.reason()).thenReturn("r");
        when(apple.getAnnotation(AILocked.class)).thenReturn(ann);
        when(zebra.getAnnotation(AILocked.class)).thenReturn(ann);

        RoundEnvironment re1 = mock(RoundEnvironment.class);
        doReturn(new LinkedHashSet<>(List.of(zebra, apple))).when(re1).getElementsAnnotatedWith(AILocked.class);
        AnnotationCollector c1 = new AnnotationCollector();
        c1.collect(re1);

        RoundEnvironment re2 = mock(RoundEnvironment.class);
        doReturn(new LinkedHashSet<>(List.of(apple, zebra))).when(re2).getElementsAnnotatedWith(AILocked.class);
        AnnotationCollector c2 = new AnnotationCollector();
        c2.collect(re2);

        assertEquals(fp(c1), fp(c2), "element insertion order must not change the fingerprint");
    }
}
