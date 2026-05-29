package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hard stop for hotfixes, temporary stubs, or quick hacks.
 * Compilation will fail once system date exceeds the expiration date.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AITemporary {
    /**
     * Expiration date in ISO format YYYY-MM-DD (e.g. "2026-06-30").
     */
    String expiresOn();

    /**
     * Rationale behind this temporary workaround.
     */
    String reason();
}
