package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated class is immutable. AI assistants must not introduce mutable state
 * (non-final fields, setters, mutating methods) when modifying this class.
 *
 * <p>Stronger than {@code @AIContext(avoids = "mutable state")} because it is a first-class
 * intent rather than a hint. The processor emits a compile-time warning when an
 * {@code @AIImmutable} class declares non-final instance fields.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AIImmutable {

    /**
     * Optional free-form note explaining the immutability design.
     * @return the note, or empty string if none
     */
    String note() default "";
}
