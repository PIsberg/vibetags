package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mandates internationalization (i18n) best practices.
 * Prohibits hardcoding of user-facing strings, labels, or messages. All user-visible text must be
 * resolved via standard localization resources or message bundles.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIInternationalized {

    /**
     * Optional rationale, persisted across AI sessions: why this rule applies to this element
     * (a past incident, a subtle invariant, a decision the next agent cannot re-derive). When set,
     * it is surfaced in the generated guardrail output.
     */
    String reason() default "";
}
