package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines architectural boundary and layering constraints.
 * Ensures the target component remains clean of invalid dependency references (e.g., domain depending on web).
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AIArchitecture {
    /**
     * Defines the architectural layer or component this class belongs to.
     */
    String belongsTo() default "";

    /**
     * Defines the list of layers or components that this class is strictly prohibited from referencing or importing.
     */
    String[] cannotReference() default {};
}
