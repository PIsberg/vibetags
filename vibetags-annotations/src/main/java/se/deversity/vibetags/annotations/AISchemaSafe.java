package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guarantees schema and serialization safety.
 * Restricts changing data formats, fields, database columns, or serialization structures
 * without explicit backward-compatible database migrations or data transformations.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface AISchemaSafe {
}
