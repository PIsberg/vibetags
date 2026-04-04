package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks critical infrastructure for continuous AI security auditing.
 * When the AI modifies this file, it must perform a security review
 * for the specified vulnerability types before outputting final code.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIAudit {
    /**
     * List of specific vulnerability types to check for.
     * Examples: "SQL Injection", "Thread Safety issues", "XSS", "CSRF"
     */
    String[] checkFor() default {};
}
