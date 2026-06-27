package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Protects compatibility/legacy bridges from unnecessary modernization or structural refactoring.
 * Indicates that the annotated component handles specific quirks, bugs, or limitations of external,
 * legacy, or upstream systems, and its structure must be preserved.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AILegacyBridge {

    /**
     * Optional rationale, persisted across AI sessions: why this rule applies to this element
     * (a past incident, a subtle invariant, a decision the next agent cannot re-derive). When set,
     * it is surfaced in the generated guardrail output.
     */
    String reason() default "";
}
