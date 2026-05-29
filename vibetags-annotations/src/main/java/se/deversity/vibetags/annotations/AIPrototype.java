package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a rapid framework prototype.
 * Relaxes standard strict quality rules (e.g. required i18n, coverage) within the class, but prevents it from leaking into stable production code.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AIPrototype {
}
