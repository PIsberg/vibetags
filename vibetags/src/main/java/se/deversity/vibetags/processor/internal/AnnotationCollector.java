package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AISecure;

// New annotations
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITemporary;

import org.jspecify.annotations.Nullable;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates annotated elements across the multiple processing rounds {@code javac} performs.
 * Each annotation type has its own {@link LinkedHashSet} (insertion-ordered for stable output).
 */
@AIContext(
    focus = "Accumulates annotated elements across multiple javac processing rounds; one LinkedHashSet per annotation type preserves insertion order for stable BuildFingerprint output",
    avoids = "Replacing LinkedHashSet with HashSet — insertion order stability is required for deterministic fingerprints across recompiles"
)
public final class AnnotationCollector {

    private final Set<Element> lockedElements      = new LinkedHashSet<>();
    private final Set<Element> contextElements     = new LinkedHashSet<>();
    private final Set<Element> ignoreElements      = new LinkedHashSet<>();
    private final Set<Element> auditElements       = new LinkedHashSet<>();
    private final Set<Element> draftElements       = new LinkedHashSet<>();
    private final Set<Element> privacyElements     = new LinkedHashSet<>();
    private final Set<Element> coreElements        = new LinkedHashSet<>();
    private final Set<Element> performanceElements  = new LinkedHashSet<>();
    private final Set<Element> contractElements     = new LinkedHashSet<>();
    private final Set<Element> testDrivenElements   = new LinkedHashSet<>();
    private final Set<Element> threadSafeElements    = new LinkedHashSet<>();
    private final Set<Element> immutableElements     = new LinkedHashSet<>();
    private final Set<Element> deprecatedElements    = new LinkedHashSet<>();
    private final Set<Element> observabilityElements = new LinkedHashSet<>();
    private final Set<Element> regulationElements    = new LinkedHashSet<>();
    private final Set<Element> parallelTestsElements     = new LinkedHashSet<>();
    private final Set<Element> legacyBridgeElements     = new LinkedHashSet<>();
    private final Set<Element> architectureElements     = new LinkedHashSet<>();
    private final Set<Element> publicApiElements        = new LinkedHashSet<>();
    private final Set<Element> strictExceptionsElements = new LinkedHashSet<>();
    private final Set<Element> strictTypesElements      = new LinkedHashSet<>();
    private final Set<Element> internationalizedElements = new LinkedHashSet<>();
    private final Set<Element> strictClasspathElements  = new LinkedHashSet<>();
    private final Set<Element> schemaSafeElements       = new LinkedHashSet<>();

    // v1.0.0 annotations
    private final Set<Element> idempotentElements       = new LinkedHashSet<>();
    private final Set<Element> featureFlagElements      = new LinkedHashSet<>();
    private final Set<Element> secureElements           = new LinkedHashSet<>();

    // New annotations fields
    private final Set<Element> callersOnlyElements      = new LinkedHashSet<>();
    private final Set<Element> sandboxOnlyElements      = new LinkedHashSet<>();
    private final Set<Element> memoryBudgetElements     = new LinkedHashSet<>();
    private final Set<Element> pureElements             = new LinkedHashSet<>();
    private final Set<Element> domainModelElements      = new LinkedHashSet<>();
    private final Set<Element> extensibleElements       = new LinkedHashSet<>();
    private final Set<Element> inputSanitizedElements   = new LinkedHashSet<>();
    private final Set<Element> secureLoggingElements    = new LinkedHashSet<>();
    private final Set<Element> explainElements          = new LinkedHashSet<>();
    private final Set<Element> prototypeElements        = new LinkedHashSet<>();
    private final Set<Element> sunsetElements           = new LinkedHashSet<>();
    private final Set<Element> temporaryElements        = new LinkedHashSet<>();

    private boolean anyAnnotationsFound = false;

    /**
     * Source positions of {@code @AILocked} elements, recorded by the processor during the
     * collection rounds (the Tree API needs a live round). LinkedHashMap so iteration matches
     * the insertion order of {@link #lockedElements}. Best-effort: elements compile under
     * non-javac compilers without positions and are simply absent from this map.
     */
    private final java.util.Map<Element, SourcePositionResolver.Position> lockedPositions =
        new java.util.LinkedHashMap<>();

    /** Records the source position of a locked element; null positions are ignored. */
    public void recordLockedPosition(Element element, SourcePositionResolver.@Nullable Position position) {
        if (position != null) {
            lockedPositions.put(element, position);
        }
    }

    /** Best-effort source position of a locked element, or {@code null} when unknown. */
    public SourcePositionResolver.@Nullable Position lockedPosition(Element element) {
        return lockedPositions.get(element);
    }

    /** Drains the round environment into our per-annotation sets. Returns true if anything was added. */
    public boolean collect(RoundEnvironment roundEnv) {
        return collect(roundEnv, null);
    }

    /**
     * Drains the round environment into our per-annotation sets, querying javac only for the
     * annotation types actually present this round.
     *
     * <p>{@code presentAnnotationFqns} is the set of fully-qualified annotation names javac reports
     * as present (built from the {@code annotations} argument of {@code process()}). When non-null,
     * {@link RoundEnvironment#getElementsAnnotatedWith} is skipped for any annotation type not in the
     * set — those queries would scan every root element only to return empty, so skipping the
     * ~33 absent types is a large allocation/time saving on big compilation units. Passing
     * {@code null} restores the original behaviour of querying every type (used by direct unit
     * tests that mock {@code getElementsAnnotatedWith} without populating {@code annotations}).
     */
    public boolean collect(RoundEnvironment roundEnv, @Nullable Set<String> presentAnnotationFqns) {
        collectInto(lockedElements, AILocked.class, roundEnv, presentAnnotationFqns);
        collectInto(contextElements, AIContext.class, roundEnv, presentAnnotationFqns);
        collectInto(ignoreElements, AIIgnore.class, roundEnv, presentAnnotationFqns);
        collectInto(auditElements, AIAudit.class, roundEnv, presentAnnotationFqns);
        collectInto(draftElements, AIDraft.class, roundEnv, presentAnnotationFqns);
        collectInto(privacyElements, AIPrivacy.class, roundEnv, presentAnnotationFqns);
        collectInto(coreElements, AICore.class, roundEnv, presentAnnotationFqns);
        collectInto(performanceElements, AIPerformance.class, roundEnv, presentAnnotationFqns);
        collectInto(contractElements, AIContract.class, roundEnv, presentAnnotationFqns);
        collectInto(testDrivenElements, AITestDriven.class, roundEnv, presentAnnotationFqns);
        collectInto(threadSafeElements, AIThreadSafe.class, roundEnv, presentAnnotationFqns);
        collectInto(immutableElements, AIImmutable.class, roundEnv, presentAnnotationFqns);
        collectInto(deprecatedElements, AIDeprecated.class, roundEnv, presentAnnotationFqns);
        collectInto(observabilityElements, AIObservability.class, roundEnv, presentAnnotationFqns);
        collectInto(regulationElements, AIRegulation.class, roundEnv, presentAnnotationFqns);
        collectInto(parallelTestsElements, AIParallelTests.class, roundEnv, presentAnnotationFqns);
        collectInto(legacyBridgeElements, AILegacyBridge.class, roundEnv, presentAnnotationFqns);
        collectInto(architectureElements, AIArchitecture.class, roundEnv, presentAnnotationFqns);
        collectInto(publicApiElements, AIPublicAPI.class, roundEnv, presentAnnotationFqns);
        collectInto(strictExceptionsElements, AIStrictExceptions.class, roundEnv, presentAnnotationFqns);
        collectInto(strictTypesElements, AIStrictTypes.class, roundEnv, presentAnnotationFqns);
        collectInto(internationalizedElements, AIInternationalized.class, roundEnv, presentAnnotationFqns);
        collectInto(strictClasspathElements, AIStrictClasspath.class, roundEnv, presentAnnotationFqns);
        collectInto(schemaSafeElements, AISchemaSafe.class, roundEnv, presentAnnotationFqns);
        collectInto(idempotentElements, AIIdempotent.class, roundEnv, presentAnnotationFqns);
        collectInto(featureFlagElements, AIFeatureFlag.class, roundEnv, presentAnnotationFqns);
        collectInto(secureElements, AISecure.class, roundEnv, presentAnnotationFqns);

        // Collect new annotations
        collectInto(callersOnlyElements, AICallersOnly.class, roundEnv, presentAnnotationFqns);
        collectInto(sandboxOnlyElements, AISandboxOnly.class, roundEnv, presentAnnotationFqns);
        collectInto(memoryBudgetElements, AIMemoryBudget.class, roundEnv, presentAnnotationFqns);
        collectInto(pureElements, AIPure.class, roundEnv, presentAnnotationFqns);
        collectInto(domainModelElements, AIDomainModel.class, roundEnv, presentAnnotationFqns);
        collectInto(extensibleElements, AIExtensible.class, roundEnv, presentAnnotationFqns);
        collectInto(inputSanitizedElements, AIInputSanitized.class, roundEnv, presentAnnotationFqns);
        collectInto(secureLoggingElements, AISecureLogging.class, roundEnv, presentAnnotationFqns);
        collectInto(explainElements, AIExplain.class, roundEnv, presentAnnotationFqns);
        collectInto(prototypeElements, AIPrototype.class, roundEnv, presentAnnotationFqns);
        collectInto(sunsetElements, AISunset.class, roundEnv, presentAnnotationFqns);
        collectInto(temporaryElements, AITemporary.class, roundEnv, presentAnnotationFqns);

        boolean added = !lockedElements.isEmpty() || !contextElements.isEmpty()
                     || !ignoreElements.isEmpty() || !auditElements.isEmpty()
                     || !draftElements.isEmpty() || !privacyElements.isEmpty()
                     || !coreElements.isEmpty() || !performanceElements.isEmpty()
                     || !contractElements.isEmpty() || !testDrivenElements.isEmpty()
                     || !threadSafeElements.isEmpty() || !immutableElements.isEmpty()
                     || !deprecatedElements.isEmpty() || !observabilityElements.isEmpty()
                     || !regulationElements.isEmpty() || !parallelTestsElements.isEmpty()
                     || !legacyBridgeElements.isEmpty() || !architectureElements.isEmpty()
                     || !publicApiElements.isEmpty() || !strictExceptionsElements.isEmpty()
                     || !strictTypesElements.isEmpty() || !internationalizedElements.isEmpty()
                     || !strictClasspathElements.isEmpty() || !schemaSafeElements.isEmpty()
                     || !idempotentElements.isEmpty() || !featureFlagElements.isEmpty()
                     || !secureElements.isEmpty()
                     || !callersOnlyElements.isEmpty() || !sandboxOnlyElements.isEmpty()
                     || !memoryBudgetElements.isEmpty() || !pureElements.isEmpty()
                     || !domainModelElements.isEmpty() || !extensibleElements.isEmpty()
                     || !inputSanitizedElements.isEmpty() || !secureLoggingElements.isEmpty()
                     || !explainElements.isEmpty() || !prototypeElements.isEmpty()
                     || !sunsetElements.isEmpty() || !temporaryElements.isEmpty();
        if (added) anyAnnotationsFound = true;
        return added;
    }

    /**
     * Adds all elements annotated with {@code type} in this round to {@code bucket}, but skips the
     * javac query entirely when {@code present} is non-null and does not contain the type's FQN
     * (i.e. javac reported the annotation as absent this round, so the query would return empty).
     */
    private static void collectInto(Set<Element> bucket,
                                    Class<? extends java.lang.annotation.Annotation> type,
                                    RoundEnvironment roundEnv,
                                    @Nullable Set<String> present) {
        if (present == null || present.contains(type.getName())) {
            bucket.addAll(roundEnv.getElementsAnnotatedWith(type));
        }
    }

    public void reset() {
        lockedElements.clear();
        contextElements.clear();
        ignoreElements.clear();
        auditElements.clear();
        draftElements.clear();
        privacyElements.clear();
        coreElements.clear();
        performanceElements.clear();
        contractElements.clear();
        testDrivenElements.clear();
        threadSafeElements.clear();
        immutableElements.clear();
        deprecatedElements.clear();
        observabilityElements.clear();
        regulationElements.clear();
        parallelTestsElements.clear();
        legacyBridgeElements.clear();
        architectureElements.clear();
        publicApiElements.clear();
        strictExceptionsElements.clear();
        strictTypesElements.clear();
        internationalizedElements.clear();
        strictClasspathElements.clear();
        schemaSafeElements.clear();
        idempotentElements.clear();
        featureFlagElements.clear();
        secureElements.clear();

        // Clear new annotations
        callersOnlyElements.clear();
        sandboxOnlyElements.clear();
        memoryBudgetElements.clear();
        pureElements.clear();
        domainModelElements.clear();
        extensibleElements.clear();
        inputSanitizedElements.clear();
        secureLoggingElements.clear();
        explainElements.clear();
        prototypeElements.clear();
        sunsetElements.clear();
        temporaryElements.clear();
        lockedPositions.clear();
        anyAnnotationsFound = false;
    }

    // Unmodifiable views: callers iterate but must not add to these sets.
    // collect() and reset() operate directly on the internal LinkedHashSet fields.
    public Set<Element> locked()        { return Collections.unmodifiableSet(lockedElements); }
    public Set<Element> context()       { return Collections.unmodifiableSet(contextElements); }
    public Set<Element> ignore()        { return Collections.unmodifiableSet(ignoreElements); }
    public Set<Element> audit()         { return Collections.unmodifiableSet(auditElements); }
    public Set<Element> draft()         { return Collections.unmodifiableSet(draftElements); }
    public Set<Element> privacy()       { return Collections.unmodifiableSet(privacyElements); }
    public Set<Element> core()          { return Collections.unmodifiableSet(coreElements); }
    public Set<Element> performance()    { return Collections.unmodifiableSet(performanceElements); }
    public Set<Element> contract()       { return Collections.unmodifiableSet(contractElements); }
    public Set<Element> testDriven()     { return Collections.unmodifiableSet(testDrivenElements); }
    public Set<Element> threadSafe()     { return Collections.unmodifiableSet(threadSafeElements); }
    public Set<Element> immutable()      { return Collections.unmodifiableSet(immutableElements); }
    public Set<Element> deprecated()     { return Collections.unmodifiableSet(deprecatedElements); }
    public Set<Element> observability()  { return Collections.unmodifiableSet(observabilityElements); }
    public Set<Element> regulation()     { return Collections.unmodifiableSet(regulationElements); }
    public Set<Element> parallelTests()     { return Collections.unmodifiableSet(parallelTestsElements); }
    public Set<Element> legacyBridge()     { return Collections.unmodifiableSet(legacyBridgeElements); }
    public Set<Element> architecture()     { return Collections.unmodifiableSet(architectureElements); }
    public Set<Element> publicApi()        { return Collections.unmodifiableSet(publicApiElements); }
    public Set<Element> strictExceptions() { return Collections.unmodifiableSet(strictExceptionsElements); }
    public Set<Element> strictTypes()      { return Collections.unmodifiableSet(strictTypesElements); }
    public Set<Element> internationalized() { return Collections.unmodifiableSet(internationalizedElements); }
    public Set<Element> strictClasspath()  { return Collections.unmodifiableSet(strictClasspathElements); }
    public Set<Element> schemaSafe()       { return Collections.unmodifiableSet(schemaSafeElements); }
    public Set<Element> idempotent()       { return Collections.unmodifiableSet(idempotentElements); }
    public Set<Element> featureFlag()      { return Collections.unmodifiableSet(featureFlagElements); }
    public Set<Element> secure()           { return Collections.unmodifiableSet(secureElements); }

    // Getters for new annotations
    public Set<Element> callersOnly()      { return Collections.unmodifiableSet(callersOnlyElements); }
    public Set<Element> sandboxOnly()      { return Collections.unmodifiableSet(sandboxOnlyElements); }
    public Set<Element> memoryBudget()     { return Collections.unmodifiableSet(memoryBudgetElements); }
    public Set<Element> pure()             { return Collections.unmodifiableSet(pureElements); }
    public Set<Element> domainModel()      { return Collections.unmodifiableSet(domainModelElements); }
    public Set<Element> extensible()       { return Collections.unmodifiableSet(extensibleElements); }
    public Set<Element> inputSanitized()   { return Collections.unmodifiableSet(inputSanitizedElements); }
    public Set<Element> secureLogging()    { return Collections.unmodifiableSet(secureLoggingElements); }
    public Set<Element> explain()          { return Collections.unmodifiableSet(explainElements); }
    public Set<Element> prototype()        { return Collections.unmodifiableSet(prototypeElements); }
    public Set<Element> sunset()           { return Collections.unmodifiableSet(sunsetElements); }
    public Set<Element> temporary()        { return Collections.unmodifiableSet(temporaryElements); }

    public boolean anyAnnotationsFound() { return anyAnnotationsFound; }

    /**
     * Every annotation bucket keyed by its {@code @AI...} annotation name, in the same order
     * {@link #collect} populates them. {@code AIGuardrailProcessor.logSummary()} iterates this
     * map instead of hand-listing one {@code logSet(...)} call per annotation, so a newly added
     * bucket only needs the single {@code put} line below — no matching edit anywhere else.
     */
    public Map<String, Set<Element>> labeledSets() {
        Map<String, Set<Element>> labeled = new LinkedHashMap<>();
        labeled.put("@AILocked", locked());
        labeled.put("@AIContext", context());
        labeled.put("@AIIgnore", ignore());
        labeled.put("@AIAudit", audit());
        labeled.put("@AIDraft", draft());
        labeled.put("@AIPrivacy", privacy());
        labeled.put("@AICore", core());
        labeled.put("@AIPerformance", performance());
        labeled.put("@AIContract", contract());
        labeled.put("@AITestDriven", testDriven());
        labeled.put("@AIThreadSafe", threadSafe());
        labeled.put("@AIImmutable", immutable());
        labeled.put("@AIDeprecated", deprecated());
        labeled.put("@AIObservability", observability());
        labeled.put("@AIRegulation", regulation());
        labeled.put("@AIParallelTests", parallelTests());
        labeled.put("@AILegacyBridge", legacyBridge());
        labeled.put("@AIArchitecture", architecture());
        labeled.put("@AIPublicAPI", publicApi());
        labeled.put("@AIStrictExceptions", strictExceptions());
        labeled.put("@AIStrictTypes", strictTypes());
        labeled.put("@AIInternationalized", internationalized());
        labeled.put("@AIStrictClasspath", strictClasspath());
        labeled.put("@AISchemaSafe", schemaSafe());
        labeled.put("@AIIdempotent", idempotent());
        labeled.put("@AIFeatureFlag", featureFlag());
        labeled.put("@AISecure", secure());
        labeled.put("@AICallersOnly", callersOnly());
        labeled.put("@AISandboxOnly", sandboxOnly());
        labeled.put("@AIMemoryBudget", memoryBudget());
        labeled.put("@AIPure", pure());
        labeled.put("@AIDomainModel", domainModel());
        labeled.put("@AIExtensible", extensible());
        labeled.put("@AIInputSanitized", inputSanitized());
        labeled.put("@AISecureLogging", secureLogging());
        labeled.put("@AIExplain", explain());
        labeled.put("@AIPrototype", prototype());
        labeled.put("@AISunset", sunset());
        labeled.put("@AITemporary", temporary());
        return labeled;
    }

    /**
     * Total number of annotated references across every annotation bucket (an element carrying two
     * annotations counts twice — it is rendered in two sections). Used to pre-size renderer output
     * buffers so large projects avoid repeated StringBuilder grow-and-copy reallocation.
     */
    public int totalAnnotatedReferences() {
        return lockedElements.size() + contextElements.size() + ignoreElements.size()
            + auditElements.size() + draftElements.size() + privacyElements.size()
            + coreElements.size() + performanceElements.size() + contractElements.size()
            + testDrivenElements.size() + threadSafeElements.size() + immutableElements.size()
            + deprecatedElements.size() + observabilityElements.size() + regulationElements.size()
            + parallelTestsElements.size() + legacyBridgeElements.size() + architectureElements.size()
            + publicApiElements.size() + strictExceptionsElements.size() + strictTypesElements.size()
            + internationalizedElements.size() + strictClasspathElements.size() + schemaSafeElements.size()
            + idempotentElements.size() + featureFlagElements.size() + secureElements.size()
            + callersOnlyElements.size() + sandboxOnlyElements.size() + memoryBudgetElements.size()
            + pureElements.size() + domainModelElements.size() + extensibleElements.size()
            + inputSanitizedElements.size() + secureLoggingElements.size() + explainElements.size()
            + prototypeElements.size() + sunsetElements.size() + temporaryElements.size();
    }
}
