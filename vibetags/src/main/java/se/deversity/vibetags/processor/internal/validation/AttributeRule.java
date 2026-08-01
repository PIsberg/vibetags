package se.deversity.vibetags.processor.internal.validation;

import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;

/**
 * A rule that reads the annotation's own attributes — a blank required string, an empty array, a
 * number outside its range.
 *
 * <p>The annotation instance is resolved once and handed to the check, so a rule body is the
 * condition and the message and nothing else. An element whose annotation javac cannot hand back
 * (which happens under mocked environments) is skipped rather than dereferenced.
 *
 * @param <A> the annotation this rule reads
 */
public final class AttributeRule<A extends Annotation> implements ValidationRule {

    /** The body of an attribute rule: the condition and the message. */
    @FunctionalInterface
    public interface Check<A extends Annotation> {
        /** Called once per element carrying {@code annotation}. */
        void check(ValidationContext ctx, Element element, A annotation);
    }

    private final Class<A> scans;
    private final Check<A> body;

    private AttributeRule(Class<A> scans, Check<A> body) {
        this.scans = scans;
        this.body = body;
    }

    /** A rule over the attributes of {@code type}. */
    public static <A extends Annotation> AttributeRule<A> of(Class<A> type, Check<A> body) {
        return new AttributeRule<>(type, body);
    }

    @Override
    public Class<? extends Annotation> scans() {
        return scans;
    }

    @Override
    public void check(ValidationContext ctx, Element element) {
        A annotation = element.getAnnotation(scans);
        if (annotation == null) {
            return;
        }
        body.check(ctx, element, annotation);
    }
}
