package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts which packages or classes are permitted to invoke this method or class.
 * Enforced by the compiler/processor to prevent AI from introducing illegal architectural bypasses.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AICallersOnly {
    /**
     * Fully qualified names or wildcard patterns of allowed callers (e.g. "com.example.service.*").
     */
    String[] value();
}
