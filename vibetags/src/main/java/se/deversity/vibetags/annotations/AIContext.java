package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides specific focus and avoidance instructions for AI assistants.
 * Guides the AI on how to work with specific classes or methods.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIContext {
    /**
     * Instructions on what the AI should focus on or prioritize.
     * @return the focus instructions
     */
    String focus() default "";

    /**
     * Instructions on what libraries, patterns, or practices the AI should avoid.
     * @return the avoidance instructions
     */
    String avoids() default "";
}
