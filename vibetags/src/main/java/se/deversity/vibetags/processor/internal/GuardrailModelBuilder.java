package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.content.TransitiveRules;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.SourceLocation;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.TransitiveRule;

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
 * The only way to make a {@link GuardrailModel}: accumulates buckets, locked positions and
 * inherited rules, then sorts, deduplicates and derives everything the model holds.
 *
 * <p><strong>The sorting here is load-bearing.</strong> Insertion order is the order
 * {@code RoundEnvironment.getElementsAnnotatedWith} returned elements in, which differs between
 * Maven and Gradle, between an IDE and a command line, and between machines, so identical sources
 * would produce different {@code CLAUDE.md} content and a different {@code BuildFingerprint}
 * depending on who compiled them. Every bucket is ordered by element identity (path, then kind),
 * the locked positions likewise, and inherited rules by {@code TransitiveRules.ORDER} after
 * deduplication.
 * {@code OutputOrderDeterminismTest} and {@code GuardrailModelOrderTest} pin it.
 *
 * <p>The model's constructor only assigns, so this ordering holds only if every model is built
 * here. {@code ArchitectureRulesTest} enforces that: no other class may call that constructor.
 */
public final class GuardrailModelBuilder {

    /**
     * The element's own value identity: the same {@code (path, kind)} pair {@code equals} uses, so
     * the ordering can never disagree with set membership.
     */
    @AILocked(reason = "Fixes the order of every bucket, so changing it rewrites every consuming build's "
        + "generated files and fingerprint with nothing failing to name the cause. Path then kind; "
        + "GuardrailModelOrderTest pins the tie-break.")
    private static final Comparator<TaggedElement> BY_IDENTITY =
        Comparator.comparing(TaggedElement::path).thenComparing(e -> e.kind().name());

    private final Map<Class<? extends Annotation>, Set<TaggedElement>> buckets = new LinkedHashMap<>();
    private final Map<TaggedElement, SourceLocation> lockedPositions = new LinkedHashMap<>();
    private final List<TransitiveRule> transitiveRules = new ArrayList<>();

    /** Records a guardrail inherited from a dependency JAR; {@code null} is ignored. */
    public GuardrailModelBuilder transitiveRule(@Nullable TransitiveRule rule) {
        if (rule != null) {
            transitiveRules.add(rule);
        }
        return this;
    }

    /** Records every rule of {@code rules}; a {@code null} collection is ignored. */
    public GuardrailModelBuilder transitiveRules(@Nullable Collection<TransitiveRule> rules) {
        if (rules != null) {
            rules.forEach(this::transitiveRule);
        }
        return this;
    }

    /** Appends {@code element} to {@code type}'s bucket; {@link #build()} orders it. */
    public GuardrailModelBuilder add(Class<? extends Annotation> type, TaggedElement element) {
        buckets.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(element);
        return this;
    }

    /** Records a locked element's source position; {@code null} positions are ignored. */
    public GuardrailModelBuilder lockedPosition(TaggedElement element, @Nullable SourceLocation location) {
        if (location != null) {
            lockedPositions.put(element, location);
        }
        return this;
    }

    /**
     * The model of everything recorded so far. Buckets are keyed in {@link GuardrailAnnotations#ALL}
     * order with empty ones left out, and each is ordered by element identity.
     */
    public GuardrailModel build() {
        Map<Class<? extends Annotation>, Set<TaggedElement>> sortedBuckets = new LinkedHashMap<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            Set<TaggedElement> bucket = buckets.get(type);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            sortedBuckets.put(type, Collections.unmodifiableSet(sorted(bucket)));
        }
        Set<String> elementIds = new LinkedHashSet<>();
        int totalAnnotatedReferences = 0;
        for (Set<TaggedElement> bucket : sortedBuckets.values()) {
            totalAnnotatedReferences += bucket.size();
            for (TaggedElement element : bucket) {
                elementIds.add(element.granularQName());
            }
        }
        // Deduplicated: the same manifest is reachable through several import prefixes, and two
        // modules of a reactor legitimately import the same library. Sorted for the same reason the
        // buckets are: discovery order is import-walk order, which is not stable across builds.
        List<TransitiveRule> rules = new ArrayList<>(new LinkedHashSet<>(transitiveRules));
        rules.sort(TransitiveRules.ORDER);
        return new GuardrailModel(sortedBuckets, sortedByKey(lockedPositions), rules, elementIds,
            totalAnnotatedReferences, !sortedBuckets.isEmpty());
    }

    /** The bucket ordered by element identity, in a set that keeps that order on iteration. */
    private static Set<TaggedElement> sorted(Set<TaggedElement> bucket) {
        List<TaggedElement> ordered = new ArrayList<>(bucket);
        ordered.sort(BY_IDENTITY);
        return new LinkedHashSet<>(ordered);
    }

    /** Same, for the locked-position map that {@code GuardrailModel.lockedPositions()} exposes. */
    private static Map<TaggedElement, SourceLocation> sortedByKey(Map<TaggedElement, SourceLocation> positions) {
        List<TaggedElement> keys = new ArrayList<>(positions.keySet());
        keys.sort(BY_IDENTITY);
        Map<TaggedElement, SourceLocation> ordered = new LinkedHashMap<>();
        for (TaggedElement key : keys) {
            ordered.put(key, positions.get(key));
        }
        return ordered;
    }
}
