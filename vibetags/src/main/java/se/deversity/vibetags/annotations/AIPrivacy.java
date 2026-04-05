package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks fields, methods, or types that handle Personally Identifiable Information (PII).
 *
 * <p>AI tools must never include the values of annotated elements in:
 * <ul>
 *   <li>Log statements or console output</li>
 *   <li>Suggested code that passes values to external APIs</li>
 *   <li>Generated test fixtures, mock data, or example code</li>
 *   <li>Error messages or stack trace suggestions</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @AIPrivacy(reason = "Contains full name and date of birth")
 * private String fullName;
 *
 * @AIPrivacy
 * public String getSocialSecurityNumber() { ... }
 *
 * @AIPrivacy(reason = "Entire class handles payment card data (PCI-DSS scope)")
 * public class PaymentDetails { ... }
 * }</pre>
 *
 * <p>Differs from {@link AIIgnore}: {@code @AIIgnore} removes an element from AI context
 * entirely; {@code @AIPrivacy} keeps the element visible for code assistance but enforces
 * strict non-disclosure of its runtime values.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIPrivacy {
    /**
     * Optional explanation of the PII category or regulatory scope.
     * Examples: "GDPR personal data", "PCI-DSS cardholder data", "HIPAA PHI"
     *
     * @return human-readable description of the sensitive data
     */
    String reason() default "Contains PII - never log, expose, or include values in suggestions.";
}
