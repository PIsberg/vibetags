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
    /** How much reasoning an agent must show before changing the annotated element. */
    enum ComplexityLevel {
        /** Full derivation: every step, and why each alternative was rejected. */
        HIGH,
        /** The reasoning behind the approach taken, without exhausting the alternatives. */
        MEDIUM,
        /** A short statement of intent, enough to review the change against. */
        LOW
    }

    /**
     * Complexity level of explanations required.
     *
     * @return how much of its reasoning an agent must show; defaults to {@link ComplexityLevel#HIGH}
     */
    ComplexityLevel value() default ComplexityLevel.HIGH;
}
