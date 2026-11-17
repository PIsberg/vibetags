package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Models derived from a {@link GuardrailModel}: the halves a test round is split into, the view
 * {@code -Avibetags.exclude} publishes, and the labelled buckets the log summary walks.
 *
 * <p>Each view is built through the model's builder, so it is sorted and deduplicated exactly like
 * the model it came from. The model itself holds data only.
 */
public final class GuardrailModelViews {

    private GuardrailModelViews() {}

    /**
     * {@code model} with only the {@linkplain GuardrailAnnotations#SAFETY safety annotations} in
     * it: what the always-loaded files render for a test round whose other guardrails went to
     * {@code TESTING.md}.
     *
     * <p>Locked positions and inherited rules come along. The positions belong to {@code @AILocked}
     * elements, which are all here; inherited rules come from dependency JARs rather than from
     * this round's sources, so they are nobody's test-code guardrails and stay where they were.
     */
    public static GuardrailModel safetyOnly(GuardrailModel model) {
        GuardrailModelBuilder view = bucketsWhere(model, GuardrailAnnotations.SAFETY::contains);
        model.lockedPositions().forEach(view::lockedPosition);
        view.transitiveRules(model.transitiveRules());
        return view.build();
    }

    /**
     * {@code model} with the {@linkplain GuardrailAnnotations#SAFETY safety annotations} taken
     * out: what {@code TESTING.md} renders. With {@link #safetyOnly} it splits the buckets in two
     * with nothing in both and nothing in neither, which {@code GuardrailModelViewsTest} holds to
     * {@link GuardrailAnnotations#ALL}.
     */
    public static GuardrailModel withoutSafety(GuardrailModel model) {
        return bucketsWhere(model, type -> !GuardrailAnnotations.SAFETY.contains(type)).build();
    }

    /**
     * {@code model} with every element {@code excluded} accepts left out of every bucket: what
     * {@code -Avibetags.exclude} publishes.
     *
     * <p>A derived view rather than a filter at collection time, and the distinction is the feature.
     * The collected model is what {@code PartialRoundDetector} compares the source ledger against,
     * so removing an element from it makes the round look as though it was never shown that source
     * and invariant 17 refuses to write anything (issue #792). Dropping it here instead leaves the
     * round complete and only the output smaller.
     *
     * <p>Locked positions follow their elements, so an excluded element leaves no entry behind in
     * {@code .vibetags-locks}. Transitive rules are kept whole: they are a dependency's text rather
     * than this project's elements, and no pattern over local names should silently drop them.
     */
    public static GuardrailModel excluding(GuardrailModel model, Predicate<TaggedElement> excluded) {
        GuardrailModelBuilder view = new GuardrailModelBuilder();
        model.buckets().forEach((type, bucket) -> bucket.forEach(element -> {
            if (!excluded.test(element)) {
                view.add(type, element);
            }
        }));
        model.lockedPositions().forEach((element, position) -> {
            if (!excluded.test(element)) {
                view.lockedPosition(element, position);
            }
        });
        view.transitiveRules(model.transitiveRules());
        return view.build();
    }

    /**
     * Every bucket keyed by its {@code @AI...} label, in {@link GuardrailAnnotations#ALL} order,
     * empty ones included. Driven by the registry, so a newly added annotation appears here with
     * no edit.
     */
    public static Map<String, Set<TaggedElement>> labeledSets(GuardrailModel model) {
        Map<String, Set<TaggedElement>> labeled = new LinkedHashMap<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            labeled.put(AnnotationLabels.label(type), model.of(type));
        }
        return labeled;
    }

    /** A builder holding every bucket of {@code model} whose annotation type {@code keep} accepts. */
    private static GuardrailModelBuilder bucketsWhere(GuardrailModel model,
                                                      Predicate<Class<? extends Annotation>> keep) {
        GuardrailModelBuilder view = new GuardrailModelBuilder();
        model.buckets().forEach((type, bucket) -> {
            if (keep.test(type)) {
                bucket.forEach(element -> view.add(type, element));
            }
        });
        return view;
    }
}
