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
    /** The class of injection the annotated value must be neutralised against. */
    enum SanitizerType {
        /** Reaches a SQL query: parameterise it, never concatenate. */
        SQL_INJECTION,
        /** Reaches rendered HTML: escape for the context it is interpolated into. */
        XSS,
        /** Reaches a filesystem path: reject traversal segments and normalise before use. */
        PATH_TRAVERSAL,
        /** Reaches an LDAP filter or DN: escape per RFC 4515/4514. */
        LDAP
    }

    /**
     * Types of vulnerabilities to sanitize against.
     *
     * @return every injection class this value must be sanitized against before it is used
     */
    SanitizerType[] value();
}
