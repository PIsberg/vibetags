package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a rapid framework prototype.
 * Relaxes standard strict quality rules (e.g. required i18n, coverage) within the class, but prevents it from leaking into stable production code.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AIPrototype {

    /**
     * Optional rationale, persisted across AI sessions: why this rule applies to this element
     * (a past incident, a subtle invariant, a decision the next agent cannot re-derive). When set,
     * it is surfaced in the generated guardrail output.
     */
    String reason() default "";
}
