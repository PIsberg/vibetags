package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Freezes the public signature of an interface, class, or method.
 * AI may change internal implementation logic, but MUST NOT alter the method name,
 * parameter types, parameter order, return type, or checked exceptions.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AIContract {
    /**
     * Explanation for why this signature is contractually frozen.
     * @return the reason the signature must not change
     */
    String reason() default "This signature is contractually frozen. Do not change method names, parameter types, parameter order, return types, or checked exceptions.";
}
