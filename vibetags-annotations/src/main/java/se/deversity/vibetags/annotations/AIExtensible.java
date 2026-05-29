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
    enum Strategy {
        STRATEGY_PATTERN,
        VISITOR_PATTERN,
        FACTORY
    }

    /**
     * Design strategy required for extending capabilities.
     */
    Strategy value() default Strategy.STRATEGY_PATTERN;
}
