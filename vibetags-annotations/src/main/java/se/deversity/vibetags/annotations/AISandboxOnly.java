package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts the target element (class or method) strictly to sandbox, dev, or mock/test environments.
 * Prevents the AI from importing or referencing sandbox utilities in production pathways.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AISandboxOnly {

    /**
     * Optional rationale, persisted across AI sessions: why this rule applies to this element
     * (a past incident, a subtle invariant, a decision the next agent cannot re-derive). When set,
     * it is surfaced in the generated guardrail output.
     */
    String reason() default "";
}
