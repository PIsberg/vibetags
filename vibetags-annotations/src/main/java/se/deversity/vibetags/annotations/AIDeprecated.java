package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the annotated element as deprecated and actively routes AI assistants to the
 * replacement. Richer than Java's {@code @Deprecated}: points at the replacement, supplies a
 * migration path, and optionally lists a removal deadline.
 *
 * <p>Where {@code @AILocked} preserves an element, {@code @AIDeprecated} marks it for
 * removal — AI assistants should suggest migrating callers to {@code replacedBy} rather than
 * extending or building on the deprecated element.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIDeprecated {

    /**
     * Fully-qualified name of the replacement element that callers should migrate to.
     * @return the replacement element FQN, or empty string if no direct replacement exists
     */
    String replacedBy() default "";

    /**
     * Free-form description of how callers should migrate from this element.
     * @return the migration guide
     */
    String migrationGuide() default "Migrate any caller to the replacement.";

    /**
     * Removal deadline (e.g., a release version like "2.0" or an ISO-8601 date like "2026-12-31").
     * Empty if no removal deadline is set.
     * @return the deadline, or empty string if not yet scheduled
     */
    String deadline() default "";
}
