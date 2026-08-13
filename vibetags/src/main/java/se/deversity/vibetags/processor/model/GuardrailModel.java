package se.deversity.vibetags.processor.model;

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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every annotated element of one compilation, snapshotted into plain data — the input every
 * platform renderer reads.
 *
 * <p>The javac-facing {@code AnnotationCollector} produces this once per generate phase; nothing
 * below this type touches {@code javax.lang.model}.
 *
 * <p><strong>Buckets are sorted by {@link TaggedElement#path()}, and that is load-bearing.</strong>
 * They used to preserve the order the collector received elements in, which is the order
 * {@code RoundEnvironment.getElementsAnnotatedWith} returned them — a {@code Set} with no specified
 * iteration order, filled by walking the round's root elements in whatever order the file manager
 * enumerated them. That order is not the same between Maven and Gradle, between an IDE and a
 * command line, or between two machines whose directory listings differ, so identical sources
 * produced different {@code CLAUDE.md} content and a different {@code BuildFingerprint} depending on
 * who compiled them. Committed guardrail files then churned every time a colleague built, and the
 * write cache missed for no reason. Sorting on the element's own stable identity makes the output a
 * function of the annotations and nothing else. {@code OutputOrderDeterminismTest} pins it.
 *
 * <p>Immutable. A model handed to the renderers cannot be changed by them, which is what makes the
 * parallel write phase safe without any copying.
 */
public final class GuardrailModel {

    /** An empty model — no annotations found. */
    public static final GuardrailModel EMPTY = builder().build();

    private final Map<Class<? extends Annotation>, Set<TaggedElement>> buckets;
    private final Map<TaggedElement, SourceLocation> lockedPositions;
    private final boolean anyAnnotationsFound;

    /**
     * Guardrails that arrived from dependency JARs rather than from this project's sources, sorted
     * by {@link TransitiveRule}'s natural order. Empty unless the project opted in with a
     * {@code .vibetags-transitive} marker file.
     */
    private final List<TransitiveRule> transitiveRules;

    /**
     * The element's own value identity — the same {@code (path, kind)} pair {@code equals} uses, so
     * the ordering can never disagree with set membership.
     */
    private static final Comparator<TaggedElement> BY_IDENTITY =
        Comparator.comparing(TaggedElement::path).thenComparing(e -> e.kind().name());

    private GuardrailModel(Builder b) {
        Map<Class<? extends Annotation>, Set<TaggedElement>> copy = new LinkedHashMap<>();
        boolean any = false;
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            Set<TaggedElement> bucket = b.buckets.get(type);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            copy.put(type, Collections.unmodifiableSet(sorted(bucket)));
            any = true;
        }
        this.buckets = Collections.unmodifiableMap(copy);
        this.lockedPositions = Collections.unmodifiableMap(sortedByKey(b.lockedPositions));
        this.anyAnnotationsFound = any;
        // Deduplicated: the same manifest is reachable through several import prefixes, and two
        // modules of a reactor legitimately import the same library. Sorted for the same reason the
        // buckets are — discovery order is import-walk order, which is not stable across builds.
        List<TransitiveRule> rules = new ArrayList<>(new LinkedHashSet<>(b.transitiveRules));
        Collections.sort(rules);
        this.transitiveRules = Collections.unmodifiableList(rules);
    }

    /** The bucket ordered by element identity, in a set that keeps that order on iteration. */
    private static Set<TaggedElement> sorted(Set<TaggedElement> bucket) {
        List<TaggedElement> ordered = new ArrayList<>(bucket);
        ordered.sort(BY_IDENTITY);
        return new LinkedHashSet<>(ordered);
    }

    /** Same, for the locked-position map that feeds the {@code .vibetags-locks} report. */
    private static Map<TaggedElement, SourceLocation> sortedByKey(Map<TaggedElement, SourceLocation> positions) {
        List<TaggedElement> keys = new ArrayList<>(positions.keySet());
        keys.sort(BY_IDENTITY);
        Map<TaggedElement, SourceLocation> ordered = new LinkedHashMap<>();
        for (TaggedElement key : keys) {
            ordered.put(key, positions.get(key));
        }
        return ordered;
    }

    /** The elements carrying {@code type}, ordered by {@link TaggedElement#path()}; empty when none do. */
    public Set<TaggedElement> of(Class<? extends Annotation> type) {
        return buckets.getOrDefault(type, Set.of());
    }

    /** Best-effort source position of a locked element, or {@code null} when unknown. */
    public @Nullable SourceLocation lockedPosition(TaggedElement element) {
        return lockedPositions.get(element);
    }

    /** True when this compilation saw at least one guardrail annotation. */
    public boolean anyAnnotationsFound() {
        return anyAnnotationsFound;
    }

    /**
     * Guardrails inherited from dependency JARs, sorted and deduplicated. Empty unless the project
     * opted into transitive discovery.
     *
     * <p>Deliberately separate from {@link #anyAnnotationsFound()}, which stays a statement about
     * <em>this</em> project's own sources. The two are combined where a caller needs "did this
     * round have anything to say at all" — see {@code AnnotationCollector.anyAnnotationsFound} —
     * but a renderer asking whether the project annotated anything itself must still get the
     * honest answer.
     */
    public List<TransitiveRule> transitiveRules() {
        return transitiveRules;
    }

    /** True when at least one dependency contributed a guardrail. */
    public boolean anyTransitiveRules() {
        return !transitiveRules.isEmpty();
    }

    /**
     * Every non-empty bucket keyed by its {@code @AI...} label, in collection order. Driven by
     * {@link GuardrailAnnotations#ALL}, so a newly added annotation appears here with no edit.
     */
    public Map<String, Set<TaggedElement>> labeledSets() {
        Map<String, Set<TaggedElement>> labeled = new LinkedHashMap<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            labeled.put(GuardrailAnnotations.label(type), of(type));
        }
        return labeled;
    }

    /**
     * Every annotated element, deduplicated, identified by the same stable name the granular rule
     * files are named after.
     *
     * <p>Persisted per module so the next compilation can tell "this module's annotations changed"
     * from "this compilation could not see this module's sources" — the difference between an edit
     * and the silent guardrail loss of issues #278/#330. Deliberately independent of whether any
     * granular service is active, since the check has to work for aggregate-only projects too.
     */
    public Set<String> elementIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Set<TaggedElement> bucket : buckets.values()) {
            for (TaggedElement element : bucket) {
                ids.add(element.granularQName());
            }
        }
        return ids;
    }

    /**
     * Total annotated references across every bucket — an element carrying two annotations counts
     * twice, because it is rendered in two sections. Used to pre-size renderer output buffers so
     * large projects avoid repeated StringBuilder grow-and-copy reallocation.
     */
    public int totalAnnotatedReferences() {
        int total = 0;
        for (Set<TaggedElement> bucket : buckets.values()) {
            total += bucket.size();
        }
        return total;
    }

    // -----------------------------------------------------------------------------------------
    // Named bucket accessors. Thin lookups over `of(...)`, kept because renderers read them as
    // method references (`GuardrailModel::audit`) and because the name says what the section is.
    // -----------------------------------------------------------------------------------------

    public Set<TaggedElement> locked()             { return of(AILocked.class); }
    public Set<TaggedElement> context()            { return of(AIContext.class); }
    public Set<TaggedElement> ignore()             { return of(AIIgnore.class); }
    public Set<TaggedElement> audit()              { return of(AIAudit.class); }
    public Set<TaggedElement> draft()              { return of(AIDraft.class); }
    public Set<TaggedElement> privacy()            { return of(AIPrivacy.class); }
    public Set<TaggedElement> core()               { return of(AICore.class); }
    public Set<TaggedElement> performance()        { return of(AIPerformance.class); }
    public Set<TaggedElement> contract()           { return of(AIContract.class); }
    public Set<TaggedElement> testDriven()         { return of(AITestDriven.class); }
    public Set<TaggedElement> threadSafe()         { return of(AIThreadSafe.class); }
    public Set<TaggedElement> immutable()          { return of(AIImmutable.class); }
    public Set<TaggedElement> deprecated()         { return of(AIDeprecated.class); }
    public Set<TaggedElement> observability()      { return of(AIObservability.class); }
    public Set<TaggedElement> regulation()         { return of(AIRegulation.class); }
    public Set<TaggedElement> parallelTests()      { return of(AIParallelTests.class); }
    public Set<TaggedElement> legacyBridge()       { return of(AILegacyBridge.class); }
    public Set<TaggedElement> architecture()       { return of(AIArchitecture.class); }
    public Set<TaggedElement> publicApi()          { return of(AIPublicAPI.class); }
    public Set<TaggedElement> strictExceptions()   { return of(AIStrictExceptions.class); }
    public Set<TaggedElement> strictTypes()        { return of(AIStrictTypes.class); }
    public Set<TaggedElement> internationalized()  { return of(AIInternationalized.class); }
    public Set<TaggedElement> strictClasspath()    { return of(AIStrictClasspath.class); }
    public Set<TaggedElement> schemaSafe()         { return of(AISchemaSafe.class); }
    public Set<TaggedElement> idempotent()         { return of(AIIdempotent.class); }
    public Set<TaggedElement> featureFlag()        { return of(AIFeatureFlag.class); }
    public Set<TaggedElement> secure()             { return of(AISecure.class); }
    public Set<TaggedElement> callersOnly()        { return of(AICallersOnly.class); }
    public Set<TaggedElement> sandboxOnly()        { return of(AISandboxOnly.class); }
    public Set<TaggedElement> memoryBudget()       { return of(AIMemoryBudget.class); }
    public Set<TaggedElement> pure()               { return of(AIPure.class); }
    public Set<TaggedElement> domainModel()        { return of(AIDomainModel.class); }
    public Set<TaggedElement> extensible()         { return of(AIExtensible.class); }
    public Set<TaggedElement> inputSanitized()     { return of(AIInputSanitized.class); }
    public Set<TaggedElement> secureLogging()      { return of(AISecureLogging.class); }
    public Set<TaggedElement> explain()            { return of(AIExplain.class); }
    public Set<TaggedElement> prototype()          { return of(AIPrototype.class); }
    public Set<TaggedElement> sunset()             { return of(AISunset.class); }
    public Set<TaggedElement> temporary()          { return of(AITemporary.class); }
    public Set<TaggedElement> generated()          { return of(AIGenerated.class); }
    public Set<TaggedElement> loadBearing()        { return of(AILoadBearing.class); }
    public Set<TaggedElement> bannedApi()          { return of(AIBannedApi.class); }
    public Set<TaggedElement> threadAffinity()     { return of(AIThreadAffinity.class); }
    public Set<TaggedElement> keepInSync()         { return of(AIKeepInSync.class); }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates buckets and locked positions; see {@code AnnotationCollector.model()}. */
    public static final class Builder {
        private final Map<Class<? extends Annotation>, Set<TaggedElement>> buckets = new LinkedHashMap<>();
        private final Map<TaggedElement, SourceLocation> lockedPositions = new LinkedHashMap<>();
        private final List<TransitiveRule> transitiveRules = new ArrayList<>();

        private Builder() {}

        /** Records a guardrail inherited from a dependency JAR; {@code null} is ignored. */
        public Builder transitiveRule(@Nullable TransitiveRule rule) {
            if (rule != null) {
                transitiveRules.add(rule);
            }
            return this;
        }

        /** Records every rule of {@code rules}; a {@code null} collection is ignored. */
        public Builder transitiveRules(@Nullable Collection<TransitiveRule> rules) {
            if (rules != null) {
                rules.forEach(this::transitiveRule);
            }
            return this;
        }

        /** Appends {@code element} to {@code type}'s bucket, preserving insertion order. */
        public Builder add(Class<? extends Annotation> type, TaggedElement element) {
            buckets.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(element);
            return this;
        }

        /** Records a locked element's source position; {@code null} positions are ignored. */
        public Builder lockedPosition(TaggedElement element, @Nullable SourceLocation location) {
            if (location != null) {
                lockedPositions.put(element, location);
            }
            return this;
        }

        public GuardrailModel build() {
            return new GuardrailModel(this);
        }
    }
}
