package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts heap allocations, autoboxing, or object instantiation inside high-performance critical sections.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIMemoryBudget {
    /** How strictly allocation is forbidden inside the annotated element. */
    enum AllocationPolicy {
        /** No heap allocation at all on this path. */
        ZERO_ALLOCATION,
        /** Primitives must stay primitive; no boxing into wrapper types. */
        NO_AUTOBOXING,
        /** No new instances; reuse buffers and pooled objects instead. */
        NO_NEW_OBJECTS
    }

    /**
     * Enforces the specified memory allocation policy.
     *
     * @return the allocation policy to hold to; defaults to {@link AllocationPolicy#ZERO_ALLOCATION}
     */
    AllocationPolicy value() default AllocationPolicy.ZERO_ALLOCATION;
}
