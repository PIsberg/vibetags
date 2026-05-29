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

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import java.util.Collections;
import java.util.LinkedHashSet;
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

    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    private boolean anyAnnotationsFound = false;

    /** Drains the round environment into our per-annotation sets. Returns true if anything was added. */
    public boolean collect(RoundEnvironment roundEnv) {
        lockedElements.addAll(roundEnv.getElementsAnnotatedWith(AILocked.class));
        contextElements.addAll(roundEnv.getElementsAnnotatedWith(AIContext.class));
        ignoreElements.addAll(roundEnv.getElementsAnnotatedWith(AIIgnore.class));
        auditElements.addAll(roundEnv.getElementsAnnotatedWith(AIAudit.class));
        draftElements.addAll(roundEnv.getElementsAnnotatedWith(AIDraft.class));
        privacyElements.addAll(roundEnv.getElementsAnnotatedWith(AIPrivacy.class));
        coreElements.addAll(roundEnv.getElementsAnnotatedWith(AICore.class));
        performanceElements.addAll(roundEnv.getElementsAnnotatedWith(AIPerformance.class));
        contractElements.addAll(roundEnv.getElementsAnnotatedWith(AIContract.class));
        testDrivenElements.addAll(roundEnv.getElementsAnnotatedWith(AITestDriven.class));
        threadSafeElements.addAll(roundEnv.getElementsAnnotatedWith(AIThreadSafe.class));
        immutableElements.addAll(roundEnv.getElementsAnnotatedWith(AIImmutable.class));
        deprecatedElements.addAll(roundEnv.getElementsAnnotatedWith(AIDeprecated.class));
        observabilityElements.addAll(roundEnv.getElementsAnnotatedWith(AIObservability.class));
        regulationElements.addAll(roundEnv.getElementsAnnotatedWith(AIRegulation.class));
        parallelTestsElements.addAll(roundEnv.getElementsAnnotatedWith(AIParallelTests.class));
        legacyBridgeElements.addAll(roundEnv.getElementsAnnotatedWith(AILegacyBridge.class));
        architectureElements.addAll(roundEnv.getElementsAnnotatedWith(AIArchitecture.class));
        publicApiElements.addAll(roundEnv.getElementsAnnotatedWith(AIPublicAPI.class));
        strictExceptionsElements.addAll(roundEnv.getElementsAnnotatedWith(AIStrictExceptions.class));
        strictTypesElements.addAll(roundEnv.getElementsAnnotatedWith(AIStrictTypes.class));
        internationalizedElements.addAll(roundEnv.getElementsAnnotatedWith(AIInternationalized.class));
        strictClasspathElements.addAll(roundEnv.getElementsAnnotatedWith(AIStrictClasspath.class));
        schemaSafeElements.addAll(roundEnv.getElementsAnnotatedWith(AISchemaSafe.class));
        idempotentElements.addAll(roundEnv.getElementsAnnotatedWith(AIIdempotent.class));
        featureFlagElements.addAll(roundEnv.getElementsAnnotatedWith(AIFeatureFlag.class));
        secureElements.addAll(roundEnv.getElementsAnnotatedWith(AISecure.class));

        // Collect new annotations
        callersOnlyElements.addAll(roundEnv.getElementsAnnotatedWith(AICallersOnly.class));
        sandboxOnlyElements.addAll(roundEnv.getElementsAnnotatedWith(AISandboxOnly.class));
        memoryBudgetElements.addAll(roundEnv.getElementsAnnotatedWith(AIMemoryBudget.class));
        pureElements.addAll(roundEnv.getElementsAnnotatedWith(AIPure.class));
        domainModelElements.addAll(roundEnv.getElementsAnnotatedWith(AIDomainModel.class));
        extensibleElements.addAll(roundEnv.getElementsAnnotatedWith(AIExtensible.class));
        inputSanitizedElements.addAll(roundEnv.getElementsAnnotatedWith(AIInputSanitized.class));
        secureLoggingElements.addAll(roundEnv.getElementsAnnotatedWith(AISecureLogging.class));
        explainElements.addAll(roundEnv.getElementsAnnotatedWith(AIExplain.class));
        prototypeElements.addAll(roundEnv.getElementsAnnotatedWith(AIPrototype.class));
        sunsetElements.addAll(roundEnv.getElementsAnnotatedWith(AISunset.class));
        temporaryElements.addAll(roundEnv.getElementsAnnotatedWith(AITemporary.class));

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
}
