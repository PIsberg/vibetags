package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Prevents AI assistants from modifying the annotated element.
 * Useful for legacy code, critical security logic, or complex algorithms.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AILocked {
    /**
     * Explanation for why the element is locked.
     * @return the reason why modifications are prohibited
     */
    String reason() default "Do not modify this code under any circumstances.";
}
