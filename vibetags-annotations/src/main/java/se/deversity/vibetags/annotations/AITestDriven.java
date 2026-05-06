package se.deversity.vibetags.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces a strict Red-Green-Refactor workflow on the annotated element.
 * AI assistants MUST NOT propose changes to this element without also
 * providing the corresponding test code update in the same response.
 * Changes without matching tests are considered incomplete.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AITestDriven {

    /** Testing frameworks the AI must use for this element. */
    enum Framework {
        /** JUnit 5 (Jupiter). */
        JUNIT_5,
        /** JUnit 4. */
        JUNIT_4,
        /** TestNG. */
        TESTNG,
        /** Mockito mocking library. */
        MOCKITO,
        /** AssertJ fluent assertions. */
        ASSERTJ,
        /** Spock Framework (Groovy). */
        SPOCK,
        /** No specific framework required. */
        NONE
    }

    /**
     * Explicit path to the corresponding test file, for cases that do
     * not follow the standard naming convention.
     * Leave empty to let the AI infer the test class via naming convention.
     * @return the test file path, or empty string for convention-based
     */
    String testLocation() default "";

    /**
     * Minimum statement-coverage percentage the AI must achieve in the
     * generated or updated tests. Defaults to 100 (all new logic covered).
     * @return coverage goal as a percentage 0-100
     */
    int coverageGoal() default 100;

    /**
     * Testing frameworks the AI must use. Multiple values may be combined
     * (e.g., {@code {Framework.JUNIT_5, Framework.MOCKITO}}).
     * @return the required frameworks
     */
    Framework[] framework() default {Framework.JUNIT_5};

    /**
     * Instruction describing how external dependencies should be handled
     * in tests (e.g., "Always mock external APIs").
     * @return the mock policy instruction, or empty string if no restriction
     */
    String mockPolicy() default "";
}
