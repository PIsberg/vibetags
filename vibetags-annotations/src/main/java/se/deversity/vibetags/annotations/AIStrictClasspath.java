package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces strict compile-time dependency and classpath constraints.
 * Prohibits dynamic class loading, custom classloaders, runtime reflection hacks, or execution
 * of dynamic, external code.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIStrictClasspath {
}
