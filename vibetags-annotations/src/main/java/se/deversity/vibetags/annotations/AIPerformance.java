package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets constraints for AI assistants, informing them that this block is
 * performance-critical hot-path code where suboptimal complexity (e.g., O(n^2))
 * is unacceptable.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIPerformance {
    /**
     * The strict time or space complexity constraint required for this logic.
     * @return the constraint
     */
    String constraint() default "Strict time/space complexity constraints apply. Suboptimal complexity is unacceptable.";
}
