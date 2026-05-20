package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that is gated behind a feature flag. AI assistants must preserve the flag
 * check and must never assume the flag is always active. Changes to this code must be
 * verified against both the enabled and disabled code paths.
 *
 * <p>Typical targets: feature-toggled endpoints, A/B-tested code paths, gradual-rollout logic.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIFeatureFlag {

    /**
     * The feature flag key/name that gates this code.
     * @return the flag key, or empty string if unspecified
     */
    String flag() default "";

    /**
     * The default value of the flag when not explicitly set (false = off by default).
     * @return true if the feature is on by default, false otherwise
     */
    boolean defaultValue() default false;
}
