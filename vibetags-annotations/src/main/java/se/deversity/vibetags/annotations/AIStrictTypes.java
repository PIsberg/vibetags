package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Prohibits loose typing (e.g., using {@code Object}, raw types, or generic {@code Map<String, Object>})
 * where well-defined, type-safe domain models or strongly-typed transfer objects should be used.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIStrictTypes {
}
