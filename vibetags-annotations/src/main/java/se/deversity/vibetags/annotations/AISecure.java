package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks security-critical code that AI assistants must not weaken. Any proposed change to
 * an element annotated with {@code @AISecure} must be reviewed for security impact before
 * being applied.
 *
 * <p>Typical targets: authentication handlers, authorization checks, cryptographic operations,
 * session management, input-validation routines.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AISecure {

    /**
     * The security aspect this element protects (e.g., "authentication", "authorization",
     * "encryption", "session-management", "input-validation").
     * @return the aspect, or empty string if unspecified
     */
    String aspect() default "";
}
