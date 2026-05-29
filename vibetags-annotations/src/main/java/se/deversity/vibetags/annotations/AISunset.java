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
     */
    Class<?> replacement() default Object.class;

    /**
     * JIRA or issue tracking ticket for deprecation/sunset progress (e.g. "DEBT-123").
     */
    String jira();
}
