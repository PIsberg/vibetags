package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Signals that a class or interface must be extended using polymorphic designs (Open-Closed Principle).
 * Prompts the AI to introduce strategy or visitor patterns rather than accumulating massive conditional/switch statements.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AIExtensible {
    /** The polymorphic route to take instead of adding another branch to a conditional. */
    enum Strategy {
        /** Add behaviour as an interchangeable implementation selected at runtime. */
        STRATEGY_PATTERN,
        /** Add behaviour as a visitor over a stable type hierarchy. */
        VISITOR_PATTERN,
        /** Add behaviour by producing a new product from a factory rather than by branching. */
        FACTORY
    }

    /**
     * Design strategy required for extending capabilities.
     *
     * @return the pattern to extend through; defaults to {@link Strategy#STRATEGY_PATTERN}
     */
    Strategy value() default Strategy.STRATEGY_PATTERN;
}
