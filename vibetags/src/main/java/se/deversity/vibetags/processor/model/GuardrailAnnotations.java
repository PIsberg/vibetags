package se.deversity.vibetags.processor.model;

import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

/**
 * The canonical, ordered list of every {@code @AI...} guardrail annotation the processor collects.
 *
 * <p>One list, one order — this is what {@code AnnotationCollector} iterates when draining a
 * processing round, what {@link GuardrailModel} keys its buckets by, and what the log summary walks.
 * Adding a guardrail annotation is a single line here plus its formatter; before this list existed
 * the same annotation had to be hand-listed in a field, a collect call, a reset call, a getter, a
 * label map, and a size sum, and the ones people forgot were always the last two.
 *
 * <p>The order is load-bearing for output stability: it fixes the order buckets are populated in,
 * and therefore the insertion order of every {@code LinkedHashSet} downstream. Appending is safe;
 * reordering changes generated files. It is deliberately <em>not</em> the order
 * {@code BuildFingerprint} hashes in — that one is pinned separately, in that class, because a
 * change to it invalidates every consumer's cached fingerprint.
 */
public final class GuardrailAnnotations {

    private GuardrailAnnotations() {}

    /** Every collected annotation type, in population order. */
    @AILocked(reason = "Append only. This order fixes the insertion order of every LinkedHashSet "
        + "downstream, so reordering or removing an entry rewrites generated files in every consuming "
        + "build, with nothing failing to name the cause. BuildFingerprint hashes in its own separately "
        + "pinned order; the two are not the same list and must not be aligned.")
    public static final List<Class<? extends Annotation>> ALL = List.of(
        AILocked.class,
        AIContext.class,
        AIIgnore.class,
        AIAudit.class,
        AIDraft.class,
        AIPrivacy.class,
        AICore.class,
        AIPerformance.class,
        AIContract.class,
        AITestDriven.class,
        AIThreadSafe.class,
        AIImmutable.class,
        AIDeprecated.class,
        AIObservability.class,
        AIRegulation.class,
        AIParallelTests.class,
        AILegacyBridge.class,
        AIArchitecture.class,
        AIPublicAPI.class,
        AIStrictExceptions.class,
        AIStrictTypes.class,
        AIInternationalized.class,
        AIStrictClasspath.class,
        AISchemaSafe.class,
        AIIdempotent.class,
        AIFeatureFlag.class,
        AISecure.class,
        AICallersOnly.class,
        AISandboxOnly.class,
        AIMemoryBudget.class,
        AIPure.class,
        AIDomainModel.class,
        AIExtensible.class,
        AIInputSanitized.class,
        AISecureLogging.class,
        AIExplain.class,
        AIPrototype.class,
        AISunset.class,
        AITemporary.class,
        AIGenerated.class,
        AILoadBearing.class,
        AIBannedApi.class,
        AIThreadAffinity.class,
        AIKeepInSync.class
    );

    /**
     * The six safety annotations: the ones an agent has to see whether or not it ever opens the
     * annotated file. They stay inline when an aggregate collapses to a scoped-rules index, they
     * rank an inherited rule as {@link TransitiveRule.Tier#SAFETY}, and they stay in the
     * always-loaded files when a test round's other guardrails are routed to {@code TESTING.md}.
     *
     * <p>One definition on purpose. Each of those three behaviours used to be free to keep its own
     * copy of this list, and two copies agree only until someone adds a seventh to one of them.
     * Membership only: nothing reads an order from it, which is why it is a {@code Set} and
     * {@link #ALL} is not.
     */
    public static final Set<Class<? extends Annotation>> SAFETY = Set.of(
        AILocked.class, AICore.class, AIPrivacy.class, AIIgnore.class, AIAudit.class, AISecure.class);

    /** The label used in the log summary and the scoped-rules index, e.g. {@code @AILocked}. */
    public static String label(Class<? extends Annotation> type) {
        return "@" + type.getSimpleName();
    }
}
