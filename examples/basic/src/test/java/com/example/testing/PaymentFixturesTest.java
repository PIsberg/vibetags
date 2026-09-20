package com.example.testing;

import se.deversity.vibetags.annotations.AIContext;

/**
 * Test-code guardrail that belongs in {@code TESTING.md}.
 *
 * <p>An agent about to write a payment test needs this; an agent changing {@code PaymentService}
 * does not, and before {@code TESTING.md} existed it paid for the line anyway, in every session,
 * in every always-loaded instruction file. That is the whole of what routing moves: advice that is
 * only true inside a test source set.
 *
 * <p>This class carries no test framework on purpose. The example declares no test dependency, so
 * {@code mvn test-compile} and {@code gradlew build} compile it as an ordinary test source set and
 * the processor sees a real test round.
 */
@AIContext(
    focus = "Build payments through PaymentFixtures, never with the PaymentRequest constructor: "
        + "the fixture is what keeps minor units and currency consistent across the suite",
    avoids = "Asserting on formatted amounts; assert on the minor-unit long"
)
public final class PaymentFixturesTest {

    private PaymentFixturesTest() {
    }
}
