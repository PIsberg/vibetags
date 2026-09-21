package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
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
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.SourceLocation;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.TransitiveRule;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregates annotated elements across the multiple processing rounds {@code javac} performs, then
 * snapshots them into the compiler-free {@link GuardrailModel} the rendering layer reads.
 *
 * <p>This is the only place that holds {@code javax.lang.model} elements between rounds. Each
 * annotation type gets its own {@link LinkedHashSet} (insertion-ordered for stable output), keyed by
 * annotation class and driven by {@link GuardrailAnnotations#ALL} — one registry rather than a
 * hand-listed field, collect call, reset call, getter, label and size term per annotation.
 *
 * @see #model()
 */
@AIContext(
    focus = "Accumulates annotated elements across multiple javac processing rounds, then snapshots them into a compiler-free GuardrailModel. Ordering is settled in GuardrailModel, which sorts every bucket by TaggedElement.path() — javac's getElementsAnnotatedWith has no specified iteration order, so anything that preserves it makes generated output depend on which machine compiled it",
    avoids = "Restoring javac's iteration order as the output order, here or in GuardrailModel — it differs between Maven and Gradle and between machines, which churns committed guardrail files and misses the write cache. OutputOrderDeterminismTest pins it"
)
public final class AnnotationCollector {

    /**
     * Insertion-ordered elements per annotation type.
     *
     * <p>Every bucket is created up front and never replaced, only cleared in place. That is
     * load-bearing: {@code AIGuardrailProcessor} holds the sets returned by {@link #locked()},
     * {@link #ignore()} and {@link #audit()} as fields initialised before the first round, and reads
     * them after the last one. Swapping a bucket for a new set — or clearing the map instead of its
     * values — would leave those fields pointing at an empty set that never fills, and the
     * orphaned-annotation warnings would silently stop firing.
     */
    private final Map<Class<? extends Annotation>, Set<Element>> buckets = new LinkedHashMap<>();

    /**
     * Source positions of {@code @AILocked} elements, recorded by the processor during the
     * collection rounds (the Tree API needs a live round). LinkedHashMap so iteration matches the
     * insertion order of the locked bucket. Best-effort: elements compile under non-javac compilers
     * without positions and are simply absent from this map.
     */
    private final Map<Element, SourceLocation> lockedPositions = new LinkedHashMap<>();

    /**
     * Guardrails read from dependency JARs this compilation. A {@link LinkedHashSet} so the same
     * manifest reached through several import prefixes contributes once; final order is settled in
     * {@link GuardrailModel}, which sorts.
     */
    private final Set<TransitiveRule> transitiveRules = new LinkedHashSet<>();

    private boolean anyAnnotationsFound;

    /**
     * Whether any round of this compilation was handed sources of its own to look at.
     *
     * <p>The distinction {@link #anyAnnotationsFound()} needs and could not otherwise make: a round
     * that saw the project's sources and found nothing is stating a fact about the code, while a
     * round that saw no sources at all is stating a fact about the build. Only the first is
     * authoritative about what the project no longer has.
     */
    private boolean sawSourceRoots;

    /** Whether any round of this compilation was handed sources of its own; see the field. */
    public boolean sawSourceRoots() {
        return sawSourceRoots;
    }

    /**
     * Whether this project opted into inheriting guardrails from its dependencies
     * ({@code .vibetags-transitive}).
     *
     * <p>Read only by {@link #anyAnnotationsFound()}, and only to bound the withdrawal case below
     * to projects where content can change without any local source changing. A project that has
     * not opted in cannot be in that situation, and keeps the older, stricter guard untouched.
     */
    private boolean transitiveOptIn;

    /**
     * Whether to compute {@link ElementSignature} for each snapshotted element.
     *
     * <p>Off by default, and that is a measurable saving rather than a micro-optimisation:
     * {@code ElementSignature.of} on a type walks every enclosed member, renders each one, and
     * sorts the result, so it is the most expensive thing done per element — and the only reader
     * is the opt-in enforcing mode ({@code -Avibetags.enforce}, issue #284). On an ordinary build,
     * with enforcement off, every one of those strings is computed and then thrown away.
     *
     * <p>The signature cannot be computed lazily instead: it needs the javac element model, which
     * is only valid while the round that produced it is live, and the model is read after the last
     * round closes. So the choice has to be made up front — which is what
     * {@link #captureSignatures(boolean)} is for. Same shape as the {@code .vibetags-locks}
     * opt-in that gates source-position resolution.
     */
    private boolean captureSignatures;

    /**
     * Whether the rounds being collected compile test code rather than main code. Carried here
     * because the content builder is handed the collector and nothing else about the compilation,
     * and the rendering layer may not ask the compiler itself (invariant 7).
     *
     * <p>False by default, which is also the answer for a round that mixes main and test sources:
     * {@code ModuleRootResolver} collapses that to {@code main}, and an unrouted round loses nothing.
     */
    private boolean testRound;

    /**
     * The snapshot of the current contents, or {@code null} when it must be rebuilt. A generate or
     * check phase asks for the model several times (fingerprint, content build, per-module output),
     * so snapshotting once per round of mutation keeps that from re-walking every element.
     */
    private @Nullable GuardrailModel memo;

    /**
     * What {@link #collect} found in the round it last ran for, keyed by annotation type.
     *
     * <p>This round only, never the accumulated buckets. The validator reports per round, so
     * handing it everything collected so far would repeat every warning on every subsequent round.
     * Replaced wholesale at the start of each collect, so a round that finds nothing leaves an
     * empty map rather than the previous round's answers.
     *
     * <p>It exists because the same query is asked three times per round: here, again by
     * {@code ValidationContext.elementsWith} for every scanned type, and a third time for
     * {@code @AILocked} by the locks-report path. Each one walks every root element. Measured on
     * examples/multimodule: 103 collect scans, 78 validation scans, 4 locks scans per build.
     */
    private final Map<Class<? extends Annotation>, Set<? extends Element>> thisRound = new LinkedHashMap<>();

    /**
     * The per-element granular bodies for {@link #memo}, or {@code null} when they must be rebuilt.
     *
     * <p>Rendered from the model alone, and the heaviest per-element render there is: 44 bucket
     * loops plus a split and a regex match per line. It was recomputed on every
     * {@code GuardrailContentBuilder.build()} — measured at 12 times for one {@code mvn verify} of
     * the three-module example, because the root build, each module's own build, every accepting
     * mirror target and each safety digest all build content from the same collected state.
     *
     * <p>Memoised beside the model rather than inside the renderer on purpose: the renderer is a
     * static singleton, and a memo there would outlive the compilation that filled it.
     */
    private @Nullable Map<TaggedElement, GranularBody> granularMemo;

    /**
     * What {@code -Avibetags.exclude} named, and the published model derived with it. Separate from
     * {@link #memo} because the two answer different questions: the unfiltered one is what the
     * source ledger is checked against, the filtered one is what gets written. See
     * {@link #publishedModel()} for why collapsing them breaks invariant 17.
     */
    private ElementExclusions exclusions = ElementExclusions.NONE;

    private @Nullable GuardrailModel publishedMemo;

    /** Creates every bucket up front, in registry order, so no caller can ever see a missing one. */
    public AnnotationCollector() {
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            buckets.put(type, new LinkedHashSet<>());
        }
    }

    /**
     * Turns structural-signature capture on or off. Call before the first round; the processor
     * enables it only when {@code -Avibetags.enforce} or {@code -Avibetags.baseline.update} is set,
     * because nothing else reads {@link TaggedElement#signature()}.
     *
     * <p>Invalidates the memoised snapshot, so flipping it between rounds is safe if unusual.
     */
    public void captureSignatures(boolean capture) {
        if (this.captureSignatures != capture) {
            this.captureSignatures = capture;
            memo = null;
            publishedMemo = null;
            granularMemo = null;
        }
    }

    /**
     * Records whether the rounds being collected compile test code. The processor sets it from
     * {@link ModuleIdentity#isTestSourceSet()} as soon as the module identity resolves.
     *
     * <p>Does not invalidate the memoised snapshot: the model holds the same elements either way,
     * and only where they are rendered differs.
     */
    public void testRound(boolean testRound) {
        this.testRound = testRound;
    }

    /** Whether these rounds compile test code; false until {@link #testRound(boolean)} says so. */
    public boolean isTestRound() {
        return testRound;
    }

    /** Records the source position of a locked element; null positions are ignored. */
    public void recordLockedPosition(Element element, @Nullable SourceLocation position) {
        if (position != null) {
            lockedPositions.put(element, position);
            memo = null;
            publishedMemo = null;
            granularMemo = null;
        }
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
     * {@link RoundEnvironment#getElementsAnnotatedWith} is skipped for any annotation type not in
     * the set — those queries would scan every root element only to return empty, so skipping the
     * absent types is a large allocation/time saving on big compilation units. Passing {@code null}
     * restores the original behaviour of querying every type (used by direct unit tests that mock
     * {@code getElementsAnnotatedWith} without populating {@code annotations}).
     *
     * @return true when any bucket is non-empty — including one filled in an earlier round, matching
     *         the historical contract callers use to decide whether this compilation saw anything
     */
    public boolean collect(RoundEnvironment roundEnv, @Nullable Set<String> presentAnnotationFqns) {
        thisRound.clear();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (presentAnnotationFqns != null && !presentAnnotationFqns.contains(type.getName())) {
                // javac reported it absent this round: the query would only return empty.
                // Deliberately not recorded — "never asked" and "asked, found nothing" are
                // different answers, and ValidationContext already short-circuits an absent type
                // from the same presentFqns set without querying, so nothing re-scans it anyway.
                continue;
            }
            Set<? extends Element> found = roundEnv.getElementsAnnotatedWith(type);
            thisRound.put(type, found);
            if (found.isEmpty()) {
                continue;
            }
            // Never null: the constructor creates one bucket per GuardrailAnnotations.ALL entry and
            // nothing ever removes one. Stated rather than assumed, because a bucket silently
            // missing here would drop every element of that annotation from the generated output.
            Set<Element> bucket = Objects.requireNonNull(buckets.get(type),
                () -> "no bucket for " + type.getName() + " — GuardrailAnnotations.ALL changed after construction");
            bucket.addAll(found);
        }

        boolean added = false;
        for (Set<Element> bucket : buckets.values()) {
            if (!bucket.isEmpty()) {
                added = true;
                break;
            }
        }
        if (added) {
            anyAnnotationsFound = true;
        }
        memo = null;
        publishedMemo = null;
        granularMemo = null;
        return added;
    }

    /**
     * Drops everything collected so far, once the generate phase has written its output.
     *
     * <p>Clears each bucket in place rather than the map: the processor holds three of these sets as
     * fields, and replacing or dropping them would leave those references pointing at nothing.
     */
    public void reset() {
        buckets.values().forEach(Set::clear);
        // The round that filled it is over; leaving it would let a later caller mistake the
        // previous round's answers for its own.
        thisRound.clear();
        lockedPositions.clear();
        transitiveRules.clear();
        anyAnnotationsFound = false;
        sawSourceRoots = false;
        memo = null;
        publishedMemo = null;
        granularMemo = null;
    }

    /**
     * True when this compilation produced guardrail content: an annotation in its own sources, or
     * a rule inherited from a dependency.
     *
     * <p>The question this answers is "did this round have anything to say?", and the writer uses
     * it as {@code hasNewRules} to decide whether an <em>existing</em> generated file may be
     * rewritten. Counting only local annotations made a project whose guardrails all come from
     * dependencies write its files exactly once, on the run that created them, and then refuse
     * every update forever after with "no annotations found in this module, preserving existing
     * rules" — so a dependency upgrade could never reach the file.
     *
     * <p>It also gates the module sidecar write, and inherited rules belong there for the same
     * reason: they are part of the body this module contributes to a reactor's merged output.
     * Sidecars are keyed per module <em>and</em> source set, so a test-compile round recording its
     * own inherited rules cannot overwrite the main compile's (issue #330).
     *
     * <p>The third term is the same argument carried to zero. Counting inherited rules let a
     * dependency upgrade that <em>changed</em> a rule reach the file, but a dependency that
     * <em>withdrew</em> its rules — or a source file that dropped the last import of the package —
     * lands back on "nothing to say", and the file froze with the retracted rule still in it,
     * attributed to the library, on a build reporting no changes. Both readings of "nothing" are
     * indistinguishable in general, which is why the guard exists; they are distinguishable here,
     * because a round handed the project's own sources has seen everything there is to see. The
     * term is bounded to projects that opted into inheritance: only there can the correct output
     * change while every local source stays byte-identical, so only there does an empty round need
     * to be believed. Everywhere else the older, stricter guard is untouched.
     *
     * <p>That bound is deliberate conservatism rather than a demonstrated requirement, and the
     * distinction is worth recording. Dropping {@code transitiveOptIn} and keeping only
     * {@code sawSourceRoots} was measured against the full suite: nothing failed except the unit
     * test that pins this expression. So the evidence does not show the reactor preservation
     * guards depend on it — the multi-module path resolves {@code hasNewRules} from the sidecars
     * of every module rather than from this method, and source-set keying already separates a
     * test-compile round from the main one (#330). The bound stays because widening the guard for
     * every project on the strength of "no test objected" is not the same as knowing it is safe,
     * and the withdrawal case does not need the extra reach.
     */
    public boolean anyAnnotationsFound() {
        return anyAnnotationsFound || !transitiveRules.isEmpty() || (transitiveOptIn && sawSourceRoots);
    }

    /**
     * Records that a round was handed at least one root element, i.e. that this compilation is
     * looking at the project's own sources rather than running over an empty source set.
     */
    public void noteSourceRoots() {
        this.sawSourceRoots = true;
    }

    /** Declares whether this project inherits guardrails from its dependencies. */
    public void transitiveOptIn(boolean optedIn) {
        this.transitiveOptIn = optedIn;
    }

    /**
     * Records guardrails read from dependency manifests, so they reach the snapshot every renderer
     * and the build fingerprint read from.
     *
     * <p>Kept here rather than threaded separately into the renderers because the model is the one
     * boundary between the javac-facing and compiler-free halves. A second channel for the same
     * kind of data would be a second thing to remember to fingerprint, and forgetting that is
     * silent: the output would simply stop tracking dependency upgrades.
     */
    public void addTransitiveRules(java.util.Collection<TransitiveRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        transitiveRules.addAll(rules);
        memo = null;
        publishedMemo = null;
        granularMemo = null;
    }

    /** The transitive rules recorded so far, deduplicated in insertion order. */
    public Set<TransitiveRule> transitiveRules() {
        return Collections.unmodifiableSet(transitiveRules);
    }

    /**
     * The compiler-free snapshot of everything collected so far — the boundary every renderer reads
     * from. Memoized until the next {@link #collect} or {@link #reset}.
     *
     * <p>Snapshotting here rather than lazily inside the renderers is deliberate: an {@code Element}
     * is only meaningful while its round is live, and the parallel write phase runs after the last
     * one has closed.
     */
    public GuardrailModel model() {
        GuardrailModel m = memo;
        if (m == null) {
            m = snapshot();
            memo = m;
        }
        return m;
    }

    /**
     * What this build actually writes: {@link #model()} minus anything {@code -Avibetags.exclude}
     * names. Memoized alongside it.
     *
     * <p>Two accessors rather than one filtered model, deliberately. {@link #model()} is what
     * {@link PartialRoundDetector} compares the source ledger against, and it must keep showing
     * every element this round collected: filter there and an excluded-but-annotated source reads
     * as one the round was never shown, so invariant 17 refuses to write anything at all. That is
     * exactly what a compiler-level {@code <exclude>} does, and why it was not a route
     * (<a href="https://github.com/PIsberg/vibetags/issues/792">issue #792</a>). Everything
     * downstream of the ledger reads this one instead.
     *
     * <p>Identical to {@link #model()} when no pattern was given, which is every build that does
     * not pass the option, so the default path allocates no second model.
     */
    public GuardrailModel publishedModel() {
        if (exclusions.isEmpty()) {
            return model();
        }
        GuardrailModel m = publishedMemo;
        if (m == null) {
            m = model().excluding(exclusions::excludes);
            publishedMemo = m;
        }
        return m;
    }

    /**
     * Sets the exclusions from {@code -Avibetags.exclude}. Called once from the processor's
     * {@code init}, before any round has run.
     */
    public void setExclusions(ElementExclusions exclusions) {
        this.exclusions = exclusions;
        this.publishedMemo = null;
        this.granularMemo = null;
    }

    /**
     * The per-element granular bodies for the current contents, rendered once per collected state.
     *
     * <p>Unmodifiable: every caller reads it, today only for its key set and to write one file per
     * entry, and sharing one map between the root build, the module builds and the mirror targets
     * only works while none of them can change it.
     *
     * <p>Rendered from {@link #publishedModel()}: an excluded element owns no scoped rule file
     * either, which in the case that motivated the option was the larger half of the output.
     */
    public Map<TaggedElement, GranularBody> granularRules() {
        Map<TaggedElement, GranularBody> rules = granularMemo;
        if (rules == null) {
            rules = Collections.unmodifiableMap(
                PlatformRendererRegistry.granularRenderer().renderGranular(publishedModel()));
            granularMemo = rules;
        }
        return rules;
    }

    /**
     * What the last {@link #collect} found for {@code type} in that round alone, or {@code null}
     * when this round was never asked about it.
     *
     * <p>{@code null} rather than an empty set on purpose: "not asked" and "asked, found nothing"
     * are different answers, and only the second lets a caller skip its own query.
     */
    public @Nullable Set<? extends Element> elementsThisRound(Class<? extends Annotation> type) {
        return thisRound.get(type);
    }

    /**
     * The whole of {@link #elementsThisRound}, for handing to the validator so it does not repeat
     * the query per scanned type. Unmodifiable: the validator reads it, nothing else may write it.
     */
    public Map<Class<? extends Annotation>, Set<? extends Element>> roundIndex() {
        return Collections.unmodifiableMap(thisRound);
    }

    /** Unmodifiable view of the elements carrying {@code type}; empty when none do. */
    public Set<Element> elementsOf(Class<? extends Annotation> type) {
        Set<Element> bucket = buckets.get(type);
        return bucket == null ? Set.of() : Collections.unmodifiableSet(bucket);
    }

    // -----------------------------------------------------------------------------------------
    // Named bucket accessors — the javac-side view, for callers that need a real Element (and for
    // the collection tests). Rendering reads model() instead.
    // -----------------------------------------------------------------------------------------

    public Set<Element> locked()             { return elementsOf(AILocked.class); }
    public Set<Element> context()            { return elementsOf(AIContext.class); }
    public Set<Element> ignore()             { return elementsOf(AIIgnore.class); }
    public Set<Element> audit()              { return elementsOf(AIAudit.class); }
    public Set<Element> draft()              { return elementsOf(AIDraft.class); }
    public Set<Element> privacy()            { return elementsOf(AIPrivacy.class); }
    public Set<Element> core()               { return elementsOf(AICore.class); }
    public Set<Element> performance()        { return elementsOf(AIPerformance.class); }
    public Set<Element> contract()           { return elementsOf(AIContract.class); }
    public Set<Element> testDriven()         { return elementsOf(AITestDriven.class); }
    public Set<Element> threadSafe()         { return elementsOf(AIThreadSafe.class); }
    public Set<Element> immutable()          { return elementsOf(AIImmutable.class); }
    public Set<Element> deprecated()         { return elementsOf(AIDeprecated.class); }
    public Set<Element> observability()      { return elementsOf(AIObservability.class); }
    public Set<Element> regulation()         { return elementsOf(AIRegulation.class); }
    public Set<Element> parallelTests()      { return elementsOf(AIParallelTests.class); }
    public Set<Element> legacyBridge()       { return elementsOf(AILegacyBridge.class); }
    public Set<Element> architecture()       { return elementsOf(AIArchitecture.class); }
    public Set<Element> publicApi()          { return elementsOf(AIPublicAPI.class); }
    public Set<Element> strictExceptions()   { return elementsOf(AIStrictExceptions.class); }
    public Set<Element> strictTypes()        { return elementsOf(AIStrictTypes.class); }
    public Set<Element> internationalized()  { return elementsOf(AIInternationalized.class); }
    public Set<Element> strictClasspath()    { return elementsOf(AIStrictClasspath.class); }
    public Set<Element> schemaSafe()         { return elementsOf(AISchemaSafe.class); }
    public Set<Element> idempotent()         { return elementsOf(AIIdempotent.class); }
    public Set<Element> featureFlag()        { return elementsOf(AIFeatureFlag.class); }
    public Set<Element> secure()             { return elementsOf(AISecure.class); }
    public Set<Element> callersOnly()        { return elementsOf(AICallersOnly.class); }
    public Set<Element> sandboxOnly()        { return elementsOf(AISandboxOnly.class); }
    public Set<Element> memoryBudget()       { return elementsOf(AIMemoryBudget.class); }
    public Set<Element> pure()               { return elementsOf(AIPure.class); }
    public Set<Element> domainModel()        { return elementsOf(AIDomainModel.class); }
    public Set<Element> extensible()         { return elementsOf(AIExtensible.class); }
    public Set<Element> inputSanitized()     { return elementsOf(AIInputSanitized.class); }
    public Set<Element> secureLogging()      { return elementsOf(AISecureLogging.class); }
    public Set<Element> explain()            { return elementsOf(AIExplain.class); }
    public Set<Element> prototype()          { return elementsOf(AIPrototype.class); }
    public Set<Element> sunset()             { return elementsOf(AISunset.class); }
    public Set<Element> temporary()          { return elementsOf(AITemporary.class); }
    public Set<Element> generated()          { return elementsOf(AIGenerated.class); }
    public Set<Element> loadBearing()        { return elementsOf(AILoadBearing.class); }
    public Set<Element> bannedApi()          { return elementsOf(AIBannedApi.class); }
    public Set<Element> threadAffinity()     { return elementsOf(AIThreadAffinity.class); }
    public Set<Element> keepInSync()         { return elementsOf(AIKeepInSync.class); }

    // -----------------------------------------------------------------------------------------
    // Snapshotting
    // -----------------------------------------------------------------------------------------

    /**
     * Materializes one {@link TaggedElement} per distinct element and files it into the same buckets.
     *
     * <p>An element carrying five annotations must produce <em>one</em> {@code TaggedElement} shared
     * by all five buckets, or the granular-rules map would group it five times. That is why the
     * annotations are gathered per element first and the model assembled second.
     */
    private GuardrailModel snapshot() {
        Map<Element, TaggedElement.Builder> builders = new LinkedHashMap<>();
        final boolean signatures = captureSignatures;
        buckets.forEach((type, elements) -> {
            for (Element e : elements) {
                record(builders.computeIfAbsent(e, k -> newBuilder(k, signatures)), e, type);
            }
        });

        Map<Element, TaggedElement> tagged = new LinkedHashMap<>();
        GuardrailModel.Builder model = GuardrailModel.builder();
        buckets.forEach((type, elements) -> {
            for (Element e : elements) {
                model.add(type, materialize(e, builders, tagged, signatures));
            }
        });
        lockedPositions.forEach((e, position) ->
            model.lockedPosition(materialize(e, builders, tagged, signatures), position));
        model.transitiveRules(transitiveRules);
        return model.build();
    }

    /**
     * Builds {@code e}'s snapshot, its owner's first. The recursion is one level deep at most:
     * {@link ElementNaming#owningElement} returns a type or a package, whose own owner is itself.
     */
    private static TaggedElement materialize(Element e,
                                             Map<Element, TaggedElement.Builder> builders,
                                             Map<Element, TaggedElement> tagged,
                                             boolean signatures) {
        TaggedElement done = tagged.get(e);
        if (done != null) {
            return done;
        }
        TaggedElement.Builder builder = builders.computeIfAbsent(e, k -> newBuilder(k, signatures));
        Element ownerElement = ElementNaming.owningElement(e);
        if (!ownerElement.equals(e)) {
            builder.owner(materialize(ownerElement, builders, tagged, signatures));
        }
        TaggedElement result = builder.build();
        tagged.put(e, result);
        return result;
    }

    /** The name forms and kind for {@code e}, computed once here and never derived again. */
    private static TaggedElement.Builder newBuilder(Element e, boolean signatures) {
        return TaggedElement.builder(ElementNaming.elementPath(e))
            .names(e.toString(),
                   ElementNaming.simpleNameOf(e),
                   ElementNaming.elementDisplayName(e),
                   ElementNaming.granularQName(e))
            .kind(tagOf(e))
            // Captured here because it needs the javac element model, which is only valid while the
            // round is live; the enforcing mode reads it later as plain data (issue #284). Skipped
            // entirely when enforcement is off — rendering the visible member set of every type is
            // the single most expensive thing done per element, and nothing else reads the result.
            .signature(signatures ? ElementSignature.of(e) : "");
    }

    /**
     * Records the annotation instance of {@code type} carried by {@code e}. Generic so the wildcard
     * captured from the registry still binds {@code getAnnotation} to that annotation's own type.
     */
    private static <A extends Annotation> void record(TaggedElement.Builder builder, Element e, Class<A> type) {
        A annotation = e.getAnnotation(type);
        // String members read as one line from here on: a text-block reason must not end a Markdown
        // bullet halfway (issue #549). Done once, at the only place an annotation enters the model.
        builder.annotation(type, SingleLineAnnotation.of(type, annotation));
        if (annotation instanceof AISunset sunset) {
            builder.typeMember("AISunset.replacement", replacementTypeName(sunset));
        }
    }

    /**
     * {@code AISunset.replacement()} is the one {@code Class}-valued annotation member VibeTags has.
     * Reading it during annotation processing throws {@link MirroredTypeException} — the class does
     * not exist as a {@code Class} object yet — so it is resolved to a type name here, at the one
     * point where the compiler is still in scope, and read back by name downstream.
     */
    private static String replacementTypeName(AISunset sunset) {
        try {
            return sunset.replacement().getName();
        } catch (MirroredTypeException mte) {
            TypeMirror mirror = mte.getTypeMirror();
            return mirror != null ? mirror.toString() : "java.lang.Object";
        }
    }

    /**
     * Maps javac's element kind onto the compiler-free tag.
     *
     * <p>{@code UNKNOWN} covers both "the compiler reported no kind" (which happens under mocked and
     * non-javac environments) and "a kind newer than this enum knows" — neither is worth failing a
     * build over, and {@code ElementTagMappingTest} fails first if the JDK grows a constant.
     */
    private static ElementTag tagOf(Element e) {
        ElementKind kind = e.getKind();
        ElementTag tag = ElementTag.fromName(kind != null ? kind.name() : null);
        return tag != null ? tag : ElementTag.UNKNOWN;
    }
}
