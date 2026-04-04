package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a class, method, or field from AI context entirely.
 * AI tools should not reference, suggest changes to, or include annotated
 * elements when generating code or answering questions.
 *
 * <p>Differs from {@link AILocked}: {@code @AILocked} prevents modification
 * while keeping the element visible; {@code @AIIgnore} removes the element
 * from AI context completely — treat it as if it does not exist.
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Auto-generated code that must not be touched or referenced</li>
 *   <li>Deprecated code kept only for backward compatibility</li>
 *   <li>Internal scaffolding irrelevant to AI-assisted development</li>
 * </ul>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AIIgnore {
    /**
     * Optional explanation for why this element is excluded from AI context.
     */
    String reason() default "Excluded from AI context.";
}
