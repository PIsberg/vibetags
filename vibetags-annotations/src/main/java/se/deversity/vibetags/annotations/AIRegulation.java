package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ties the annotated element to a specific compliance requirement (GDPR, PCI-DSS, HIPAA, SOX,
 * etc.). Stronger than {@code @AIAudit} because it names the exact regulatory clause: AI
 * assistants must document the compliance impact of any change and must not weaken the
 * requirement.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIRegulation {

    /**
     * The compliance standard the element implements (e.g., {@code "GDPR"}, {@code "PCI-DSS"},
     * {@code "HIPAA"}, {@code "SOX"}).
     * @return the standard name
     */
    String standard();

    /**
     * The specific clause, article, or section within the standard (e.g., {@code "Art. 17"} for
     * GDPR right-to-erasure).
     * @return the clause identifier, or empty string if none
     */
    String clause() default "";

    /**
     * Free-form description of what the element does to satisfy the requirement.
     * @return the description
     */
    String description() default "Implements a regulatory requirement. Any change must document its compliance impact.";
}
