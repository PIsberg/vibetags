package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated class or method is explicitly designed to be thread-safe and names
 * the strategy used to achieve that guarantee. AI assistants must preserve the synchronization
 * invariant when modifying this code and must document the reasoning behind any change.
 *
 * <p>This is different from {@code @AIAudit} (which says "check for bugs"); {@code @AIThreadSafe}
 * declares an existing design invariant that must not be broken.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIThreadSafe {

    /** The thread-safety strategy used to guarantee correctness under concurrent access. */
    enum Strategy {
        /** Coarse-grained {@code synchronized} blocks or methods. */
        SYNCHRONIZED,
        /** Lock-free algorithms based on atomics, CAS, or volatile reads. */
        LOCK_FREE,
        /** Immutable state — no mutation after construction. */
        IMMUTABLE,
        /** Per-thread state held in {@link ThreadLocal}. */
        THREAD_LOCAL,
        /** Other strategy not covered by the standard enum values. */
        OTHER
    }

    /**
     * The strategy used to provide the thread-safety guarantee.
     * @return the synchronization strategy
     */
    Strategy strategy() default Strategy.SYNCHRONIZED;

    /**
     * Optional free-form note explaining the synchronization design (e.g., "guarded by stateLock").
     * @return the note, or empty string if none
     */
    String note() default "";
}
