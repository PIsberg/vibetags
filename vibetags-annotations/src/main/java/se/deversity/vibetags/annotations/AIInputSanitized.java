package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces sanitization pipelines on input parameters or fields before they reach queries, HTML renderers, or files.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface AIInputSanitized {
    enum SanitizerType {
        SQL_INJECTION,
        XSS,
        PATH_TRAVERSAL,
        LDAP
    }

    /**
     * Types of vulnerabilities to sanitize against.
     */
    SanitizerType[] value();
}
