package se.deversity.vibetags.processor.internal.validation;

import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;

/**
 * The commonest shape of guardrail defect: two annotations on the same element that contradict each
 * other, or where one makes the other a no-op.
 *
 * <p>Roughly two dozen of these exist. Written one {@code for} loop at a time they were the bulk of
 * the validator and the reason it read as a single 450-line method; written as a table they are one
 * line each, and adding the next one is a line rather than a loop.
 *
 * <p>{@code scans} is the annotation whose elements are visited and {@code other} the one looked up
 * on each of them. The pair is not symmetric in cost: {@code scans} decides which
 * {@code getElementsAnnotatedWith} query runs, so it should be the rarer of the two.
 */
public final class PairRule implements ValidationRule {

    private final Class<? extends Annotation> scans;
    private final Class<? extends Annotation> other;
    private final boolean advisory;
    private final String message;

    private PairRule(Class<? extends Annotation> scans, Class<? extends Annotation> other,
                     boolean advisory, String message) {
        this.scans = scans;
        this.other = other;
        this.advisory = advisory;
        this.message = message;
    }

    /**
     * A warning when an element carries both annotations.
     *
     * @param message the text following the element's name, starting with its own leading space
     */
    public static PairRule warn(Class<? extends Annotation> scans, Class<? extends Annotation> other, String message) {
        return new PairRule(scans, other, false, message);
    }

    /** As {@link #warn}, but advisory: the pair is suspicious rather than wrong. */
    public static PairRule note(Class<? extends Annotation> scans, Class<? extends Annotation> other, String message) {
        return new PairRule(scans, other, true, message);
    }

    @Override
    public Class<? extends Annotation> scans() {
        return scans;
    }

    @Override
    public void check(ValidationContext ctx, Element element) {
        if (element.getAnnotation(other) == null) {
            return;
        }
        // Anchored at the *other* annotation's mirror: resolving a contradiction means removing
        // one of the two annotations, so the caret belongs on an annotation line, not on the
        // declaration the IDE would otherwise highlight.
        if (advisory) {
            ctx.note(element, other, element + message);
        } else {
            ctx.warn(element, other, element + message);
        }
    }

    /** The annotation looked up on each visited element. Read by the registry's own coverage test. */
    public Class<? extends Annotation> other() {
        return other;
    }
}
