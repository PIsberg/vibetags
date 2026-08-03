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
    /** What may appear in a log line in place of the annotated value. */
    enum MaskingPolicy {
        /** Nothing: the value never reaches a log, not even redacted. */
        OMIT,
        /** A stable one-way hash, so occurrences can be correlated without revealing the value. */
        HASH,
        /** Card-number shape: all but the last four digits replaced. */
        MASK_CREDIT_CARD,
        /** Address shape: the local part masked, the domain kept. */
        MASK_EMAIL
    }

    /**
     * Logging policy to apply (hashing, fully omitting, or masking certain shapes).
     *
     * @return what may appear in a log line in place of this value; defaults to
     *         {@link MaskingPolicy#OMIT}
     */
    MaskingPolicy value() default MaskingPolicy.OMIT;
}
