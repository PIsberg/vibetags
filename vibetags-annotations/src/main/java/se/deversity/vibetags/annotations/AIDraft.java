package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks methods or classes that are intended for AI implementation.
 * Surfaces instructions to the AI assistant in a dedicated "IMPLEMENTATION TASKS" section.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIDraft {
    /**
     * Detailed instructions for the AI on how to implement the annotated element.
     * @return the implementation instructions
     */
    String instructions() default "Implement this method/class according to standard practices.";
}
