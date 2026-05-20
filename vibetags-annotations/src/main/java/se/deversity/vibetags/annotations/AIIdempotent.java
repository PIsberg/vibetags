package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated method or type guarantees idempotency: calling it multiple times
 * produces the same result as calling it once. AI assistants must never introduce side effects
 * that would break this guarantee (e.g., unconditional inserts, non-idempotent state mutations).
 *
 * <p>Typical targets: REST PUT/DELETE handlers, message-queue consumers, distributed saga steps.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIIdempotent {

    /**
     * Optional explanation of why idempotency is required or how it is guaranteed.
     * @return the reason, or empty string if none
     */
    String reason() default "";
}
