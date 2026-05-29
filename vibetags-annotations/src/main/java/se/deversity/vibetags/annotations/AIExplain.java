package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces step-by-step mathematical/architectural Chain-of-Thought (CoT) explanations of any modifications.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIExplain {
    enum ComplexityLevel {
        HIGH,
        MEDIUM,
        LOW
    }

    /**
     * Complexity level of explanations required.
     */
    ComplexityLevel value() default ComplexityLevel.HIGH;
}
