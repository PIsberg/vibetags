package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ultra-strict deprecation guardrail.
 * AI models are strictly prohibited from adding any new references/calls to elements annotated with {@code @AISunset}.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AISunset {
    /**
     * Fully qualified class replacement for the sunset API element.
     *
     * @return the type callers should migrate to, or {@code Object.class} when no direct
     *         replacement exists
     */
    Class<?> replacement() default Object.class;

    /**
     * JIRA or issue tracking ticket for deprecation/sunset progress (e.g. "DEBT-123").
     *
     * @return the ticket tracking the removal, so the guardrail points at the work rather than
     *         only at the prohibition
     */
    String jira();
}
