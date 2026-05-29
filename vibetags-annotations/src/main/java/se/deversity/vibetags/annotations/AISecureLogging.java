package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Protects sensitive variables from being logged directly or leaked in console outputs.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface AISecureLogging {
    enum MaskingPolicy {
        OMIT,
        HASH,
        MASK_CREDIT_CARD,
        MASK_EMAIL
    }

    /**
     * Logging policy to apply (hashing, fully omitting, or masking certain shapes).
     */
    MaskingPolicy value() default MaskingPolicy.OMIT;
}
