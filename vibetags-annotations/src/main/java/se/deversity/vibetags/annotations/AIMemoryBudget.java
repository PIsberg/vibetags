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
    enum AllocationPolicy {
        ZERO_ALLOCATION,
        NO_AUTOBOXING,
        NO_NEW_OBJECTS
    }

    /**
     * Enforces the specified memory allocation policy.
     */
    AllocationPolicy value() default AllocationPolicy.ZERO_ALLOCATION;
}
